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

import org.mockito.Mockito.when
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.mockito.MockitoSugar
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport
import uk.gov.hmrc.servicemetrics.model.Environment
import uk.gov.hmrc.servicemetrics.config.SlackNotificationsConfig

import java.time.Instant
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.DurationInt

class MongoQueryNotificationRepositorySpec
  extends AnyWordSpec
     with Matchers
     with MockitoSugar
     with DefaultPlayMongoRepositorySupport[MongoQueryNotificationRepository.MongoQueryNotification]:

  private val mockSlackNotificationsConfig = mock[SlackNotificationsConfig]
  when(mockSlackNotificationsConfig.throttlingPeriod)
    .thenReturn(7.days)

  override val repository: MongoQueryNotificationRepository =
    MongoQueryNotificationRepository(mongoComponent, mockSlackNotificationsConfig)

  private def seed(env: Environment) =
    Seq(
      MongoQueryNotificationRepository.MongoQueryNotification(
        timestamp   = Instant.now(),
        service     = "service",
        database    = "database",
        queryType   = MongoQueryLogHistoryRepository.MongoQueryType.SlowQuery,
        environment = env,
        team        = "team"
      )
    )

  "hasBeenNotified" should:
    "return true" when:
      "there are notifications for a service, collection, environment and query type" in:
        val team        = "team"
        val environment = Environment.QA

        repository.flagAsNotified(seed(environment)).futureValue

        repository.hasBeenNotified(team).futureValue shouldBe true
