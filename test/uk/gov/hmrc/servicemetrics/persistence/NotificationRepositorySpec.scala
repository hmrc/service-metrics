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

package uk.gov.hmrc.servicemetrics.persistence

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.mockito.MockitoSugar
import play.api.Configuration
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport
import uk.gov.hmrc.servicemetrics.config.AppConfig
import uk.gov.hmrc.servicemetrics.model.Environment

import java.time.Instant
import scala.concurrent.ExecutionContext.Implicits.global

class NotificationRepositorySpec
  extends AnyWordSpec
     with Matchers
     with MockitoSugar
     with DefaultPlayMongoRepositorySupport[NotificationRepository.Notification]:

  override val repository: NotificationRepository =
    NotificationRepository(Configuration("alerts.slack.throttling-period" -> "7.days"), mongoComponent)

  "hasBeenNotified" should:
    "return true" when:
      "there are notifications for a service, collection, environment and query type" in:
        val team = "team"
        val environment  = Environment.QA
        val item =
          NotificationRepository.Notification(
            timestamp   = Instant.now()
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
          , environment = environment
          , team        = team
          )

        repository.flagAsNotified(Seq(item)).futureValue
        repository.hasBeenNotified(team, AppConfig.LogMetricId.SlowRunningQuery).futureValue shouldBe true
