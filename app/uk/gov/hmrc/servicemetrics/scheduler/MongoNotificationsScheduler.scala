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
import uk.gov.hmrc.servicemetrics.persistence.MongoQueryLogHistoryRepository.MongoQueryType
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
    val from = to.minus(slackNotifiactionsConfig.notificationPeriod.toDays, ChronoUnit.DAYS)

    logger.info(s"Starting to notify teams of non performant mongo queries on ${envs.mkString(", ")}")
    for {
      _ <- Future.traverse(envs)(notifyPerEnvironment(_, from, to))
    } yield logger.info(s"Finished notifying of non performant mongo queries on ${envs.mkString(", ")}")
  }

  private[scheduler] def notifyPerEnvironment(
    env : Environment,
    from: Instant,
    to  : Instant,
  ) =
    for {
      nonPerformantQueries <- mongoService.getAllQueries(env, from, to)
      notificationData     =  nonPerformantQueries
                                .map(npq => (npq.database, npq.collection, npq.service, npq.queryType))
                                .distinct
      _                    <- notificationData.foldLeftM[Future, Seq[SlackNotificationResponse]](Seq.empty){
                                case (acc, (database, collection, service, queryType)) =>
                                  val message = s"""
                                                  |The service *$service* is running non performant queries against the collection *$database.$collection* in *${env.asString}*
                                                  |Please, proceed to Kibana for more details:
                                                  |<${kibanaLink(queryType, service, env)}|${queryType.value}}>
                                                """.stripMargin
                                  mongoService.hasBeenNotified(
                                    collection, env, service, queryType
                                  ).flatMap(hasBeenNotified =>
                                    if (hasBeenNotified){
                                      logger.info(s"Notification for service '$service', collection '$database.$collection' and query type '${queryType.value}' was triggered already.")
                                      Future.successful(Seq.empty[SlackNotificationResponse])
                                    } else {
                                      if (slackNotifiactionsConfig.enabled)
                                        slackNotificationsConnector.sendMessage(
                                          SlackNotificationRequest(
                                            OwningTeams(database), //TODO: make sure we don't need to translate database to repository name
                                            MessageDetails(message)
                                          )
                                        ).map(_ +: acc)
                                      else {
                                        logger.info(message)
                                        Future.successful(Seq.empty)
                                      }
                                    }
                                  )
                                }
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
