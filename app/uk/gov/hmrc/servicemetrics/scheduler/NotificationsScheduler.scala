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
import play.api.{Configuration, Logger}
import play.api.inject.ApplicationLifecycle
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.mongo.TimestampSupport
import uk.gov.hmrc.mongo.lock.{ScheduledLockService, MongoLockRepository}
import uk.gov.hmrc.servicemetrics.config.AppConfig
import uk.gov.hmrc.servicemetrics.connector._
import uk.gov.hmrc.servicemetrics.persistence.{LogHistoryRepository, NotificationRepository}
import uk.gov.hmrc.servicemetrics.service.MetricsService

import java.time.{DayOfWeek, Instant, LocalDateTime}
import java.time.temporal.ChronoUnit
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

@Singleton
class NotificationsScheduler  @Inject()(
  config                     : Configuration
, appConfig                  : AppConfig
, lockRepository             : MongoLockRepository
, timestampSupport           : TimestampSupport
, metricsService             : MetricsService
, slackNotificationsConnector: SlackNotificationsConnector
, notificationRepository     : NotificationRepository
)(using
  ActorSystem
, ApplicationLifecycle
, ExecutionContext
) extends SchedulerUtils:

  override val logger = Logger(getClass)

  private given HeaderCarrier = HeaderCarrier()

  private val schedulerConfig =
    SchedulerConfig(config, "scheduler.notifications")

  private val notifyTeams         : Boolean     = config.get[Boolean]("alerts.slack.notify-teams")
  private val notificationChannels: Seq[String] = config.get[Seq[String]]("alerts.slack.notification-channels")

  scheduleWithLock(
    label           = "Notifications Scheduler"
  , schedulerConfig = schedulerConfig
  , lock            = ScheduledLockService(lockRepository, "notifications-scheduler", timestampSupport, schedulerConfig.interval)
  ):
    if duringWorkingHours(LocalDateTime.now()) then
      logger.info(s"Starting to notify teams")
      val to = Instant.now()
      for
        from <- notificationRepository
                  .lastInsertDate()
                  .map(_.getOrElse(to.minus(3, ChronoUnit.DAYS)))
        xs   <- metricsService.teamLogs(from, to)
        _    <- xs.flatMap: (team, logs) =>
                    logs
                      .groupBy(_.logType.logMetricId)
                      .map((logMetricId, logs) => (team, logMetricId, logs))
                  .toSeq
                  .foldLeftM(()):
                    case (_, (team, logMetricId, logs)) =>
                      notifyAndRecord(team, logMetricId, logs).recover:
                        case NonFatal(e) => logger.error(s"Failed to notify team: $team - ${e.getMessage}", e)
      yield logger.info(s"Finished notifying teams")
    else
      logger.info("Notifications are disabled during non-working hours")
      Future.unit

  private[scheduler] def duringWorkingHours(now: LocalDateTime): Boolean =
    !Seq(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY).contains(now.getDayOfWeek()) && (9 to 17).contains(now.getHour)

  private[scheduler] def notifyAndRecord(team: String, logMetricId: AppConfig.LogMetricId, logs: Seq[LogHistoryRepository.LogHistory]): Future[Unit] =
    notificationRepository.hasBeenNotified(team, logMetricId).flatMap:
      case true  => logger.info(s"Notifications for team: $team and logMetricId: ${logMetricId.asString} were already triggered")
                    Future.unit
      case false => for
                      _  <- Future.successful:
                              if      logs.exists(_.logType.logMetricId != logMetricId) then sys.error(s"Logs: $logs should only contain logMetricId: ${logMetricId.asString}")
                              else if logs.isEmpty                                      then sys.error(s"Logs are empty for logMetricId: ${logMetricId.asString}")
                              else ()
                      msg = appConfig.createMessage(team, logMetricId, logs)
                      _  <- if   notificationChannels.nonEmpty
                            then notifyChannel(SlackNotificationsConnector.ChannelLookup.SlackChannels(notificationChannels), msg)
                            else Future.unit
                      _  <- if   notifyTeams
                            then notifyChannel(SlackNotificationsConnector.ChannelLookup.GithubTeam(team), msg)
                            else Future.successful(Nil)
                      _  <- notificationRepository.flagAsNotified:
                              logs.map: l =>
                                NotificationRepository.Notification(l.service, l.environment, l.logType, Instant.now(), team)
                    yield logger.info(s"Notifications for team: $team and logMetricId: ${logMetricId.asString} has been sent")

  private def notifyChannel(
    channelLookup: SlackNotificationsConnector.ChannelLookup,
    messages     : Seq[String],
  ): Future[Unit] =
    slackNotificationsConnector.sendMessage(SlackNotificationsConnector.Request(
      channelLookup = channelLookup
    , text          = "PlatOps notification of Kibana logs"
    , emoji         = ":see_no_evil:"
    , displayName   = "Platops Kibana Monitoring"
    , blocks        = SlackNotificationsConnector.toBlocks(messages)
    ))
