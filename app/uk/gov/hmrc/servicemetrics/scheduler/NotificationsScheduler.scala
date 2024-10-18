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
import uk.gov.hmrc.mongo.lock.{LockService, MongoLockRepository}
import uk.gov.hmrc.servicemetrics.config.AppConfig
import uk.gov.hmrc.servicemetrics.connector._
import uk.gov.hmrc.servicemetrics.persistence.{LogHistoryRepository, NotificationRepository}
import uk.gov.hmrc.servicemetrics.service.MetricsService

import java.time.{DayOfWeek, Instant, LocalDateTime}
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.Duration

@Singleton
class NotificationsScheduler  @Inject()(
  config                     : Configuration
, appConfig                  : AppConfig
, lockRepository             : MongoLockRepository
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
  private val notificationPeriod  : Duration    = config.get[Duration]("alerts.slack.notification-period")
  private val notificationChannels: Seq[String] = config.get[Seq[String]]("alerts.slack.notification-channels")

  scheduleWithLock(
    label           = "Notifications Scheduler"
  , schedulerConfig = schedulerConfig
  , lock            = LockService(lockRepository, "notifications-scheduler", schedulerConfig.interval)
  ):
    if duringWorkingHours(LocalDateTime.now()) then
      val to   = Instant.now()
      val from = to.minusSeconds(notificationPeriod.toSeconds)
      logger.info(s"Starting to notify teams based on log detection")
      notify(from, to).map: _ =>
        logger.info(s"Finished notifying teams based on log detection in")
    else
      logger.info("Notifications are disabled during non-working hours")
      Future.unit

  private[scheduler] def duringWorkingHours(now: LocalDateTime): Boolean =
    !Seq(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY).contains(now.getDayOfWeek()) && (9 to 17).contains(now.getHour)

  private[scheduler] def notify(from: Instant, to: Instant) =
    for
      teamLogs <- metricsService.teamLogs(from, to).map(_.toSeq)
      toNotify <- teamLogs.foldLeftM(Seq.empty[(String, Seq[LogHistoryRepository.LogHistory])]):
                    case (acc, (team, logs)) =>
                      notificationRepository.hasBeenNotified(team).map: alreadyNotified =>
                        logger.info(s"Notifications for team '$team' ${if alreadyNotified then "were already triggered" else "needs to be sent"}")
                        if   alreadyNotified
                        then acc
                        else acc :+ (team, logs)
      _        <- toNotify.foldLeftM(()):
                    case (acc, (team, logs)) =>
                      val msg = appConfig.createMessage(team, logs)
                      ( for
                          _ <- if   notificationChannels.nonEmpty
                               then notifyChannel(SlackNotificationsConnector.ChannelLookup.SlackChannels(notificationChannels), msg)
                               else Future.unit
                          _ <- if   notifyTeams
                               then notifyChannel(SlackNotificationsConnector.ChannelLookup.GithubTeam(team), msg)
                               else Future.successful(Nil)
                          _ <- notificationRepository.flagAsNotified:
                                 logs.map: l =>
                                   NotificationRepository.Notification(l.service, l.environment, l.logType, Instant.now(), team)
                        yield ()
                      ).recoverWith:
                        case scala.util.control.NonFatal(e) =>
                          logger.error(s"Failed to notify team: $team based on log detection", e)
                          Future.unit
    yield ()

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
