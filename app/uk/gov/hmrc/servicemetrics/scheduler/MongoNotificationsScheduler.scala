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

import cats.implicits._
import org.apache.pekko.actor.ActorSystem
import play.api.Logger
import play.api.inject.ApplicationLifecycle
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.mongo.lock.{LockService, MongoLockRepository}
import uk.gov.hmrc.servicemetrics.config.{SchedulerConfigs}
import uk.gov.hmrc.servicemetrics.config.SlackNotificationsConfig
import uk.gov.hmrc.servicemetrics.model.Environment
import uk.gov.hmrc.servicemetrics.connector._
import uk.gov.hmrc.servicemetrics.persistence.MongoQueryLogHistoryRepository.{MongoQueryLogHistory, MongoQueryType}
import uk.gov.hmrc.servicemetrics.persistence.MongoQueryNotificationRepository.MongoQueryNotification
import uk.gov.hmrc.servicemetrics.service.MongoService

import java.net.URLEncoder
import java.time.{DayOfWeek, Instant, LocalDateTime}
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
, slackNotificationsConfig   : SlackNotificationsConfig
)(using
  ActorSystem
, ApplicationLifecycle
, ExecutionContext
) extends SchedulerUtils:

  override val logger = Logger(getClass)

  private given HeaderCarrier = HeaderCarrier()

  scheduleWithLock(
    label           = "MongoNotificationsScheduler",
    schedulerConfig = schedulerConfig.mongoNotificationsScheduler,
    lock            = LockService(lockRepository, "mongo-notifications-scheduler", 30.minutes)
  ):
    val envs: List[Environment] =
      Environment.values.toList.filterNot(_.equals(Environment.Integration))
    val to   = Instant.now()
    val from = to.minus(slackNotificationsConfig.notificationPeriod.toHours, ChronoUnit.HOURS)

    if duringWorkingHours() then
      logger.info(s"Starting to notify teams of non-performant mongo queries on ${envs.mkString(", ")}")
      for
        _ <- envs.foldLeftM(()): (_, env) =>
               notifyPerEnvironment(env, from, to)
                 .recoverWith:
                   case scala.util.control.NonFatal(e) =>
                     logger.error(s"Failed to notify teams of non-performant mongo queries on ${env.asString}", e)
                     Future.unit
      yield logger.info(s"Finished notifying of non-performant mongo queries on ${envs.mkString(", ")}")
    else
      logger.info("Notifications are disabled during non-working hours")
      Future.unit

  private[scheduler] def duringWorkingHours(overrideNow: Option[LocalDateTime] = None): Boolean =
    val now = overrideNow.getOrElse(LocalDateTime.now())
    !Seq(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY).contains(now.getDayOfWeek())
      && (9 to 17).contains(now.getHour)

  private[scheduler] def notifyPerEnvironment(
    env : Environment,
    from: Instant,
    to  : Instant,
  ) =
    for
      nonPerformantQueries    <- mongoService.getAllQueriesGroupedByTeam(env, from, to)
      notificationData        <- nonPerformantQueries.toSeq.foldLeftM[Future, Seq[(String, Seq[MongoQueryLogHistory])]](Seq.empty):
                                   case (acc, (team, notificationData)) =>
                                     mongoService.hasBeenNotified(team).map: hasBeenNotified =>
                                       if hasBeenNotified then
                                         logger.info(s"Notifications for team '$team' were already triggered.")
                                         acc
                                       else
                                         acc :+ (team, notificationData)
      mongoQueryNotifications <- notificationData.foldLeftM[Future, Seq[MongoQueryNotification]](Seq.empty):
                                   case (acc, (team, notifications)) =>
                                     if slackNotificationsConfig.notifyTeams || slackNotificationsConfig.notificationChannels.nonEmpty then
                                       val notificationChannelsLookup = Option.when(slackNotificationsConfig.notificationChannels.nonEmpty)(ChannelLookup.SlackChannels(slackNotificationsConfig.notificationChannels))
                                       val teamChannelLookup          = Option.when(slackNotificationsConfig.notifyTeams)(ChannelLookup.GithubTeam(team))
                                       (notificationChannelsLookup ++ teamChannelLookup).toList
                                         .foldLeftM[Future, Seq[MongoQueryNotification]](acc): (a, cl) =>
                                           notifyChannel(cl, notifications, team, env).map(_ ++ a)
                                     else
                                       logger.info(s"Detected non-performant queries for team '$team'")
                                       Future.successful(acc)
      _                       <-
                                 if mongoQueryNotifications.nonEmpty then
                                   mongoService.flagAsNotified(mongoQueryNotifications)
                                 else
                                   Future.unit
    yield ()

  private def notifyChannel(
    channelLookup: ChannelLookup,
    notifications: Seq[MongoQueryLogHistory],
    team         : String,
    env          : Environment,
  ): Future[Seq[MongoQueryNotification]] =
    val initialMessage = s"""Hi *$team*, we have seen the following non-performant queries""".stripMargin
    val messages =
      notifications
        .groupBy(_.queryType)
        .foldLeft(Seq(initialMessage)):
          case (acc, (qt, ns)) =>
            (acc :+ qt.value) :+
              ns
                .map: n =>
                  s"• service *${n.service}* in *${n.environment.asString}* - <${kibanaLink(qt, n.database, n.environment)}|see kibana>"
                .distinct
                .mkString("\n")

    val request = SlackNotificationRequest(
      channelLookup = channelLookup,
      text          = "There are non-performant queries running against MongoDB",
      emoji         = ":see_no_evil:",
      displayName   = s"Non-performant queries",
      blocks        = SlackNotificationRequest.toBlocks(messages)
    )

    slackNotificationsConnector.sendMessage(request)
      .collect:
        case response if response.errors.isEmpty =>
          logger.info(s"Creating notification to save $team $notifications")
          notifications.map: nd =>
            MongoQueryNotification(
              service     = nd.service,
              database    = nd.database,
              environment = env,
              queryType   = nd.queryType,
              timestamp   = Instant.now(),
              team        = team
            )
        case response =>
          logger.error(s"Errors occurred when sending a slack notification ${response.errors}")
          Seq.empty

  private def kibanaLink(
    queryType  : MongoQueryType,
    service    : String,
    environment: Environment
  ): String =
    slackNotificationsConfig.kibanaLinks(queryType.value)
      .replace(s"$${env}"    , URLEncoder.encode(environment.asString, "UTF-8"))
      .replace(s"$${service}", URLEncoder.encode(service             , "UTF-8"))
