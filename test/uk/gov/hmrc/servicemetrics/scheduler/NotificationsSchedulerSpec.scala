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

import org.apache.pekko.actor.ActorSystem
import com.typesafe.config.ConfigFactory
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{times, verify, when}
import org.scalacheck.Gen
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import play.api.Configuration
import play.api.inject.ApplicationLifecycle
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.mongo.lock.MongoLockRepository
import uk.gov.hmrc.servicemetrics.config.AppConfig
import uk.gov.hmrc.servicemetrics.model.Environment
import uk.gov.hmrc.servicemetrics.connector._
import uk.gov.hmrc.servicemetrics.persistence.{LogHistoryRepository, NotificationRepository}
import uk.gov.hmrc.servicemetrics.service.MetricsService

import java.time._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.Future


class NotificationsSchedulerSpec
  extends AnyWordSpec
     with Matchers
     with ScalaFutures
     with MockitoSugar
     with IntegrationPatience
     with ScalaCheckPropertyChecks:

  "notify" should:
    "notify teams of logs" when:
      "there are logs to be notified of and notifications are enabled" in new MongoNotificationsSchedulerFixture(
        withQueries = true
      ):
        val env  = Environment.QA
        val from = Instant.now()
        val to   = from.plusSeconds(3600)

        scheduler.notify(from, to).futureValue

        verify(mockMetricsService, times(1))
          .teamLogs(any[Instant], any[Instant])
        verify(mockNotificationRepository, times(1))
          .hasBeenNotified(any[String])
        verify(mockSlackNotificationsConnector, times(2))
          .sendMessage(any[SlackNotificationsConnector.Request])(using any[HeaderCarrier])
        verify(mockNotificationRepository, times(1))
          .flagAsNotified(any[Seq[NotificationRepository.Notification]])

    "do not notify teams logs queries" when:
      "there are no logs" in new MongoNotificationsSchedulerFixture():
        val env  = Environment.QA
        val from = Instant.now()
        val to   = from.plusSeconds(3600)

        scheduler.notify(from, to).futureValue

        verify(mockMetricsService, times(1))
          .teamLogs(any[Instant], any[Instant])
        verify(mockNotificationRepository, times(0))
          .hasBeenNotified(any[String])
        verify(mockSlackNotificationsConnector, times(0))
          .sendMessage(any[SlackNotificationsConnector.Request])(using any[HeaderCarrier])

      "notification has been already triggered for this team" in new MongoNotificationsSchedulerFixture(
        withQueries     = true,
        hasBeenNotified = true
      ):
        val env  = Environment.QA
        val from = Instant.now()
        val to   = from.plusSeconds(3600)

        scheduler.notify(from, to).futureValue

        verify(mockMetricsService, times(1))
          .teamLogs(any[Instant], any[Instant])
        verify(mockNotificationRepository, times(1))
          .hasBeenNotified(any[String])
        verify(mockSlackNotificationsConnector, times(0))
          .sendMessage(any[SlackNotificationsConnector.Request])(using any[HeaderCarrier])

      "there is no notification channels and not notifying to teams" in new MongoNotificationsSchedulerFixture(
        withQueries          = true,
        notifyTeams          = false,
        notificationChannels = Seq.empty
      ):
        val env  = Environment.QA
        val from = Instant.now()
        val to   = from.plusSeconds(3600)

        scheduler.notify(from, to).futureValue

        verify(mockMetricsService, times(1))
          .teamLogs(any[Instant], any[Instant])
        verify(mockNotificationRepository, times(1))
          .hasBeenNotified(any[String])
        verify(mockSlackNotificationsConnector, times(0))
          .sendMessage(any[SlackNotificationsConnector.Request])(using any[HeaderCarrier])

      "there is no notification channels" in new MongoNotificationsSchedulerFixture(
        withQueries            = true,
        notifyTeams            = true,
        notificationChannels   = Seq.empty
      ):
        val env  = Environment.QA
        val from = Instant.now()
        val to   = from.plusSeconds(3600)

        scheduler.notify(from, to).futureValue

        verify(mockMetricsService, times(1))
          .teamLogs(any[Instant], any[Instant])
        verify(mockNotificationRepository, times(1))
          .hasBeenNotified(any[String])
        verify(mockSlackNotificationsConnector, times(1))
          .sendMessage(any[SlackNotificationsConnector.Request])(using any[HeaderCarrier])

    "runs only during working hours" in new MongoNotificationsSchedulerFixture:
      val yearDays = for (yearDay <- Gen.choose(1   , 365 )) yield yearDay
      val years    = for (year    <- Gen.choose(2023, 2030)) yield year
      val hours    = for (hour    <- Gen.choose(0   , 23  )) yield hour
      val minutes  = for (minute  <- Gen.choose(0   , 59  )) yield minute

      forAll(yearDays, years, hours, minutes): (yearDay: Int, year: Int, hour: Int, minute: Int) =>
        whenever(
             (LocalDate.of(year, 1, 1).isLeapYear && yearDay < 366)
          || !LocalDate.of(year, 1, 1).isLeapYear
        ):
          val date = LocalDate.ofYearDay(year, yearDay)
          val time = LocalTime.of(hour, minute, 0)

          val dateTime = LocalDateTime.of(date, time)

          if   date.getDayOfWeek != DayOfWeek.SATURDAY
            && date.getDayOfWeek != DayOfWeek.SUNDAY
            && time.getHour <= 17
            && time.getHour >= 9
          then
            scheduler.duringWorkingHours(dateTime) shouldBe true
          else
            scheduler.duringWorkingHours(dateTime) shouldBe false

  abstract class MongoNotificationsSchedulerFixture(
    withQueries           : Boolean     = false,
    hasBeenNotified       : Boolean     = false,
    notifyTeams           : Boolean     = true,
    notificationChannels  : Seq[String] = Seq("channel")
  ) extends MockitoSugar:

    given ActorSystem          = ActorSystem()
    given ApplicationLifecycle = mock[ApplicationLifecycle]

    val schedulerConfig =
      SchedulerConfig(
        "foo",
        true,
        1.second,
        1.second
      )

    val config = Configuration(ConfigFactory.parseString(s"""
      |mongo-collection-size-history.frequency.days = 1
      |
      |microservice.services.elasticsearch.long-running-query-in-milliseconds = 3000
      |
      |scheduler.notifications {
      |  enabled      = true
      |  interval     = 1.day
      |  initialDelay = 1.second
      |}
      |
      |alerts {
      |  slack {
      |    notification-period   = 1.days
      |    throttling-period     = 2.hours
      |    notify-teams          = $notifyTeams
      |    notification-channels = [${notificationChannels.mkString(",")}]
      |    kibana {
      |      baseUrl = "http://logs.$${env}.local"
      |      links  = {
      |        slow-running-query = "http://url"
      |        non-indexed-query  = "http://url"
      |        unsafe-content     = "http://url"
      |      }
      |    }
      |  }
      |}
      |""".stripMargin))

    val mockMongoLockRepository          = mock[MongoLockRepository]
    val mockMetricsService               = mock[MetricsService]
    val mockSlackNotificationsConnector  = mock[SlackNotificationsConnector]
    val mockNotificationRepository       = mock[NotificationRepository]

    val scheduler = NotificationsScheduler(
      config                      = config
    , appConfig                   = AppConfig(config)
    , lockRepository              = mockMongoLockRepository
    , metricsService              = mockMetricsService
    , slackNotificationsConnector = mockSlackNotificationsConnector
    , notificationRepository      = mockNotificationRepository
    )

    val queries: Map[String, Seq[LogHistoryRepository.LogHistory]] =
      if withQueries then
        Map("team" -> Seq(LogHistoryRepository.LogHistory(
          timestamp   = Instant.now()
        , since       = Instant.now().minusSeconds(20)
        , service     = "service"
        , logType     = LogHistoryRepository.LogType.AverageMongoDuration(
                          AppConfig.LogMetricId.SlowRunningQuery
                        , Seq(
                            LogHistoryRepository.LogType.AverageMongoDuration.MongoDetails(
                              database    = "database"
                            , collection  = "collection"
                            , duration    = 3001
                            , occurrences = 1
                            )
                          )
                        )
        , environment = Environment.QA
        , teams       = Seq("team")
        )))
      else
        Map.empty

    when(mockMetricsService.teamLogs(any[Instant], any[Instant]))
      .thenReturn(Future.successful(queries))

    when(mockNotificationRepository.hasBeenNotified(any[String]))
      .thenReturn(Future.successful(hasBeenNotified))

    when(mockNotificationRepository.flagAsNotified(any[Seq[NotificationRepository.Notification]]))
      .thenReturn(Future.unit)

    when(mockSlackNotificationsConnector.sendMessage(any[SlackNotificationsConnector.Request])(using any[HeaderCarrier]))
      .thenReturn(Future.unit)
