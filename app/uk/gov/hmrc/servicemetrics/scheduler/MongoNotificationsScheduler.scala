/*
 * Copyright 2023 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.servicemetrics.scheduler

import akka.actor.ActorSystem
import cats.implicits._
import play.api.Logger
import play.api.inject.ApplicationLifecycle
import uk.gov.hmrc.mongo.lock.{LockService, MongoLockRepository}
import uk.gov.hmrc.servicemetrics.config.{SchedulerConfigs}
import uk.gov.hmrc.servicemetrics.config.SlackNotificationsConfig
import uk.gov.hmrc.servicemetrics.model.Environment
import uk.gov.hmrc.servicemetrics.connector._
import uk.gov.hmrc.servicemetrics.persistence.MongoQueryLogHistoryRepository.{MongoQueryLogHistory, MongoQueryType, NonPerformantQueryDetails}
import uk.gov.hmrc.servicemetrics.persistence.MongoQueryNotificationRepository.MongoQueryNotification
import uk.gov.hmrc.servicemetrics.service.MongoService

import java.net.URLEncoder
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.DurationInt

@Singleton
class MongoNotificationsScheduler  @Inject()(
  schedulerConfig            : SchedulerConfigs
, lockRepository             : MongoLockRepository
, mongoService               : MongoService
, slackNotificationsConnector: SlackNotificationsConnector
, slackNotifiactionsConfig   : SlackNotificationsConfig
)(implicit
  actorSystem          : ActorSystem
, applicationLifecycle : ApplicationLifecycle
, ec                   : ExecutionContext
) extends SchedulerUtils {

  override val logger = Logger(getClass)

  scheduleWithLock(
    label           = "MongoNotificationsScheduler",
    schedulerConfig = schedulerConfig.mongoNotificationsScheduler,
    lock            = LockService(lockRepository, "mongo-notifications-scheduler", 30.minutes)
  ) {
    val envs: List[Environment] =
      Environment.values.filterNot(_.equals(Environment.Integration))
    val to   = Instant.now
    val from = to.minus(slackNotifiactionsConfig.notificationPeriod.toHours, ChronoUnit.HOURS)

    logger.info(s"Starting to notify teams of non performant mongo queries on ${envs.mkString(", ")}")
    for {
      _ <- Future.traverse(envs)(env =>
        notifyPerEnvironment(env, from, to)
          .recoverWith {
            case scala.util.control.NonFatal(e) =>
              logger.error(s"Failed to notify teams of non performant mongo queries on ${env.asString}", e)
              Future.unit
          }
      )
    } yield logger.info(s"Finished notifying of non performant mongo queries on ${envs.mkString(", ")}")
  }

  type MongoNotificationData = (String, String, String, MongoQueryType)

  private[scheduler] def notifyPerEnvironment(
    env : Environment,
    from: Instant,
    to  : Instant,
  ) =
    for {
      nonPerformantQueries    <- mongoService.getAllQueriesGroupedByTeam(env, from, to)
      notificationData        <- nonPerformantQueries.toSeq.foldLeftM[Future, Seq[(String, Seq[MongoQueryLogHistory])]](Seq.empty){
                                  case (acc, (team, notificationData)) =>                                       
                                        mongoService.hasBeenNotified(team).map(hasBeenNotified =>
                                          if (hasBeenNotified){
                                            logger.info(s"Notifications for team '$team' were already triggered.")
                                            acc
                                          } else {
                                            acc :+ (team, notificationData)
                                          }
                                        )
                                    }
      mongoQueryNotifications <- notificationData.foldLeftM[Future, Seq[MongoQueryNotification]](Seq.empty) { case (acc, (team, notifications)) => 
                                  if (slackNotifiactionsConfig.enabled) {
                                    val channelLookup = if (slackNotifiactionsConfig.notifyTeams)
                                        GithubTeam(team)
                                      else
                                        // SlackChannels(Seq("team-platops-alerts"))
                                        SlackChannels(Seq("test-alerts-channel"))
                                    val blocks = notifications.flatMap{nd =>
                                      nd.details.flatMap { case NonPerformantQueryDetails(collection, _, _) =>
                                        val message = s"""
                                                          |The service *${nd.service}* is running non performant queries against the collection *${nd.database}.$collection* in *${env.asString}*
                                                          |Please click on the following Kibana links for more details:
                                                        """.stripMargin
                                        SlackNotificationRequest.toBlocks(
                                          message,
                                          Some(
                                            new java.net.URL(kibanaLink(nd.queryType, nd.database, env)) -> nd.queryType.value
                                          )
                                        )
                                      }
                                    }
                                    val request = SlackNotificationRequest(
                                      channelLookup = channelLookup,
                                      text          = "There are non-performant queries running against MongoDB",
                                      emoji         = ":see_no_evil:",
                                      displayName   = s"Non performant queries [$team]",
                                      blocks        = blocks
                                    )

                                    slackNotificationsConnector.sendMessage(request)
                                      .collect {
                                        case response if response.errors.isEmpty => 
                                          logger.info(s"Creating notification to save $team $notifications")
                                          notifications.map(nd =>
                                            MongoQueryNotification(
                                              service     = nd.service,
                                              database    = nd.database,
                                              environment = env,
                                              queryType   = nd.queryType,
                                              timestamp   = Instant.now(),
                                              team        = team
                                            )
                                           ) ++ acc
                                        case response => 
                                          logger.error(s"Errors occurred when sending a slack notification ${response.errors}")
                                          acc
                                      }
                                  } else {
                                    logger.info(s"Detected non performant queries for team '$team'")
                                    Future.successful(acc)
                                  }
                                }
      _                       <- if (mongoQueryNotifications.nonEmpty)
                                  mongoService.flagAsNotified(mongoQueryNotifications)
                                else
                                  Future.unit
    } yield ()

  private def kibanaLink(
    queryType  : MongoQueryType,
    service    : String,
    environment: Environment
  ): String =
    slackNotifiactionsConfig.kibanaLinks(queryType.value)
      .replace(s"$${env}", URLEncoder.encode(environment.asString, "UTF-8"))
      .replace(s"$${service}", URLEncoder.encode(service, "UTF-8"))
}
