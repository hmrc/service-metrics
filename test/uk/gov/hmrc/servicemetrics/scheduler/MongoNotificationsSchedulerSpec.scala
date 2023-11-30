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
import com.typesafe.config.ConfigFactory
import org.mockito.MockitoSugar
import org.mockito.ArgumentMatchers._
import org.scalacheck.Gen
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import play.api.Configuration
import play.api.inject.ApplicationLifecycle
import uk.gov.hmrc.mongo.lock.MongoLockRepository
import uk.gov.hmrc.servicemetrics.config.{SchedulerConfig, SchedulerConfigs}
import uk.gov.hmrc.servicemetrics.config.SlackNotificationsConfig
import uk.gov.hmrc.servicemetrics.model.Environment
import uk.gov.hmrc.servicemetrics.connector._
import uk.gov.hmrc.servicemetrics.persistence.{MongoQueryLogHistoryRepository, MongoQueryNotificationRepository}
import uk.gov.hmrc.servicemetrics.service.MongoService

import java.time._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.Future


class MongoNotificationsSchedulerSpec
  extends AnyWordSpec
  with Matchers
  with ScalaFutures
  with MockitoSugar 
  with IntegrationPatience
  with ScalaCheckPropertyChecks {


  "notifyPerEnvironment" should {
    "notify teams of non-performant queries" when {
      "there are non-performant queries to be notified of and notifications are enabled" in new MongoNotificationsSchedulerFixture(
        queries = Map("team" -> Seq(MongoQueryLogHistoryRepository.MongoQueryLogHistory(
          timestamp   = Instant.now,
          since       = Instant.now.minusSeconds(20),
          details     = Seq(MongoQueryLogHistoryRepository.NonPerformantQueryDetails(
            collection  = "collection",
            duration    = 3001,
            occurrences = 1
          )),
          database    = "database",
          service     = "service",
          queryType   = MongoQueryLogHistoryRepository.MongoQueryType.SlowQuery,
          environment = Environment.QA,
          teams       = Seq("team")
        )))
      ) {
        val env  = Environment.QA
        val from = Instant.now()
        val to   = from.plusSeconds(3600)

        scheduler.notifyPerEnvironment(
          env,
          from,
          to  
        ).futureValue

        verify(mockMongoService, times(1)).getAllQueriesGroupedByTeam(any[Environment], any[Instant], any[Instant])
        verify(mockMongoService, times(1)).hasBeenNotified(any[String])
        verify(mockMongoService, times(1)).flagAsNotified(any[Seq[MongoQueryNotificationRepository.MongoQueryNotification]])
        verify(mockSlackNotificationsConnector, times(2)).sendMessage(any[SlackNotificationRequest])
      }
    }
    "do not notify teams of performant queries" when {
      "there are no non-performant queries" in new MongoNotificationsSchedulerFixture(){
        val env  = Environment.QA
        val from = Instant.now()
        val to   = from.plusSeconds(3600)

        scheduler.notifyPerEnvironment(
          env,
          from,
          to  
        ).futureValue

        verify(mockMongoService, times(1)).getAllQueriesGroupedByTeam(any[Environment], any[Instant], any[Instant])
        verify(mockMongoService, times(0)).hasBeenNotified(any[String])
        verify(mockSlackNotificationsConnector, times(0)).sendMessage(any[SlackNotificationRequest])
      }
      "notification has been already triggered for this service, environment, collection and query type" in new MongoNotificationsSchedulerFixture(
        hasBeenNotified = true,
        queries = Map("team" -> Seq(MongoQueryLogHistoryRepository.MongoQueryLogHistory(
          timestamp   = Instant.now,
          since       = Instant.now.minusSeconds(20),
          details     = Seq(MongoQueryLogHistoryRepository.NonPerformantQueryDetails(
            collection  = "collection",
            duration    = 3001,
            occurrences = 1
          )),
          database    = "database",
          service     = "service",
          queryType   = MongoQueryLogHistoryRepository.MongoQueryType.SlowQuery,
          environment = Environment.QA,
          teams       = Seq("team")
        )))
      ){
        val env  = Environment.QA
        val from = Instant.now()
        val to   = from.plusSeconds(3600)

        scheduler.notifyPerEnvironment(
          env,
          from,
          to  
        ).futureValue

        verify(mockMongoService, times(1)).getAllQueriesGroupedByTeam(any[Environment], any[Instant], any[Instant])
        verify(mockMongoService, times(1)).hasBeenNotified(any[String])
        verify(mockSlackNotificationsConnector, times(0)).sendMessage(any[SlackNotificationRequest])
      }
      "there is no notification channels and not notifying to teams" in new MongoNotificationsSchedulerFixture(
        areNotificationEnabled = false,
        notifyTeams            = false,
        notificationChannels   = Seq.empty,
        queries = Map("team" -> Seq(MongoQueryLogHistoryRepository.MongoQueryLogHistory(
          timestamp   = Instant.now,
          since       = Instant.now.minusSeconds(20),
          database    = "database",
          details     = Seq(MongoQueryLogHistoryRepository.NonPerformantQueryDetails(
            collection  = "collection",
            duration    = 3001,
            occurrences = 1
          )),
          service     = "service",
          queryType   = MongoQueryLogHistoryRepository.MongoQueryType.SlowQuery,
          environment = Environment.QA,
          teams       = Seq("team")
        )))
      ){
        val env  = Environment.QA
        val from = Instant.now()
        val to   = from.plusSeconds(3600)

        scheduler.notifyPerEnvironment(
          env,
          from,
          to  
        ).futureValue

        verify(mockMongoService, times(1)).getAllQueriesGroupedByTeam(any[Environment], any[Instant], any[Instant])
        verify(mockMongoService, times(1)).hasBeenNotified(any[String])
        verify(mockSlackNotificationsConnector, times(0)).sendMessage(any[SlackNotificationRequest])
        
      }
      "there is no notification channels" in new MongoNotificationsSchedulerFixture(
        areNotificationEnabled = true,
        notifyTeams            = true,
        notificationChannels   = Seq.empty,
        queries = Map("team" -> Seq(MongoQueryLogHistoryRepository.MongoQueryLogHistory(
          timestamp   = Instant.now,
          since       = Instant.now.minusSeconds(20),
          database    = "database",
          details     = Seq(MongoQueryLogHistoryRepository.NonPerformantQueryDetails(
            collection  = "collection",
            duration    = 3001,
            occurrences = 1
          )),
          service     = "service",
          queryType   = MongoQueryLogHistoryRepository.MongoQueryType.SlowQuery,
          environment = Environment.QA,
          teams       = Seq("team")
        )))
      ){
        val env  = Environment.QA
        val from = Instant.now()
        val to   = from.plusSeconds(3600)

        scheduler.notifyPerEnvironment(
          env,
          from,
          to  
        ).futureValue

        verify(mockMongoService, times(1)).getAllQueriesGroupedByTeam(any[Environment], any[Instant], any[Instant])
        verify(mockMongoService, times(1)).hasBeenNotified(any[String])
        verify(mockSlackNotificationsConnector, times(1)).sendMessage(any[SlackNotificationRequest])
        
      }
    }

    "runs only during working hours" in new MongoNotificationsSchedulerFixture {
      val yearDays = for (yearDay <- Gen.choose(1, 365)) yield yearDay
      val years = for (year <- Gen.choose(2023, 2030)) yield year
      val hours = for (hour <- Gen.choose(0, 23)) yield hour
      val minutes = for (minute <- Gen.choose(0, 59)) yield minute


      forAll(yearDays, years, hours, minutes) { (yearDay: Int, year: Int, hour: Int, minute: Int) =>

        whenever((LocalDate.of(year, 1, 1).isLeapYear && yearDay < 366) || !LocalDate.of(year, 1, 1).isLeapYear) {
          val date = LocalDate.ofYearDay(year, yearDay)
          val time = LocalTime.of(hour, minute, 0)

          val dateTime = LocalDateTime.of(date, time)

          if (date.getDayOfWeek != DayOfWeek.SATURDAY
            && date.getDayOfWeek != DayOfWeek.SUNDAY
            && time.getHour <= 17
            && time.getHour >= 9
          ) {
            scheduler.duringWorkingHours(Some(dateTime)) shouldBe true
          } else {
            scheduler.duringWorkingHours(Some(dateTime)) shouldBe false
          }
        }
      }

    }
  }

  abstract class MongoNotificationsSchedulerFixture(
    queries               : Map[String, Seq[MongoQueryLogHistoryRepository.MongoQueryLogHistory]] = Map.empty,
    hasBeenNotified       : Boolean                                                  = false,
    areNotificationEnabled: Boolean                                                  = true,
    notifyTeams           : Boolean                                                  = true,
    notificationChannels  : Seq[String]                                              = Seq("channel"),
  ) extends MockitoSugar {
    implicit val system                 = ActorSystem()
    implicit val applicationLifeCyble   = mock[ApplicationLifecycle]
    val schedulerConfig                 = SchedulerConfig(
      "foo",
      true,
      1.second,
      1.second
    )
    val config                          = Configuration(ConfigFactory.parseString(s"""
      |mongo-metrics-scheduler {
      |  enabled      = true
      |  interval     = 1.hour
      |  initialDelay = 1.second
      |}
      |
      |mongo-notifications-scheduler {
      |  enabled      = true
      |  interval     = 1.day
      |  initialDelay = 1.second
      |}
      |alerts {
      |  slack {
      |    auth-token = token
      |    notification-period = 1.days
      |    throttling-period   = 7.days
      |    notify-teams = $notifyTeams
      |    notification-channels = [${notificationChannels.mkString(",")}]
      |
      |    kibana {
      |      baseUrl = "http://logs.$${env}.local"
      |      links  = {
      |        "Slow Running Query"           = "http://url"
      |        "Non-indexed Collection Query" = "http://url"
      |      }
      |    }
      |  }
      |}
      |feature {
      |  collect-non-performant-queries-enabled = false
      |}
      |""".stripMargin))
    val schedulerConfigs                = new SchedulerConfigs(config)
    val mockMongoLockRepository         = mock[MongoLockRepository]
    val mockMongoService                = mock[MongoService]
    val mockSlackNotificationsConnector = mock[SlackNotificationsConnector]
    val slackNotificationsConfig        = new SlackNotificationsConfig(config)
    val scheduler = new MongoNotificationsScheduler(
      schedulerConfig             = schedulerConfigs,
      lockRepository              = mockMongoLockRepository,
      mongoService                = mockMongoService,
      slackNotificationsConnector = mockSlackNotificationsConnector,
      slackNotificationsConfig    = slackNotificationsConfig,
    )

    when(mockMongoService.getAllQueriesGroupedByTeam(any[Environment], any[Instant], any[Instant]))
      .thenReturn(Future.successful(queries))

    when(mockMongoService.hasBeenNotified(any[String]))
      .thenReturn(Future.successful(hasBeenNotified))

    when(mockMongoService.flagAsNotified(any[Seq[MongoQueryNotificationRepository.MongoQueryNotification]]))
      .thenReturn(Future.unit)
    
    when(mockSlackNotificationsConnector.sendMessage(any[SlackNotificationRequest]))
      .thenReturn(Future.successful(SlackNotificationResponse(List.empty)))
  }

}
