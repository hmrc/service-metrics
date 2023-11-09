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

import com.typesafe.config.ConfigFactory
import play.api.Configuration
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport
import uk.gov.hmrc.servicemetrics.model.Environment

import java.time.Instant
import scala.concurrent.ExecutionContext.Implicits.global
import uk.gov.hmrc.servicemetrics.config.SlackNotificationsConfig

class MongoQueryNotificationRepositorySpec
  extends AnyWordSpec
  with Matchers
  with DefaultPlayMongoRepositorySupport[MongoQueryNotificationRepository.MongoQueryNotification] {

  private val config = Configuration(ConfigFactory.parseString(s"""
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
    |    enabled = true
    |    notification-period = 1.days
    |    throttling-period   = 7.days
    |    notify-teams = false
    |
    |    kibana {
    |      baseUrl = "http://logs.$${env}.local"
    |      links  = {
    |        "Slow Running Query"           = "url"
    |        "Non Indexed Collection Query" = "url"
    |      }
    |    }
    |  }
    |}
    |""".stripMargin))
  override lazy val repository = new MongoQueryNotificationRepository(mongoComponent, new SlackNotificationsConfig(config))

  private def seed(env: Environment) = Seq(
    MongoQueryNotificationRepository.MongoQueryNotification(
      timestamp   = Instant.now,
      service     = "service",
      queryType   = MongoQueryLogHistoryRepository.MongoQueryType.SlowQuery,
      environment = env,
      team        = "team"
    )
  )


  "hasBeenNotified" should {
    "return true" when {
      "there are notifications for a service, collection, environment and query type" in {
        
        val team        = "team"
        val environment = Environment.QA

        repository.insertMany(seed(environment)).futureValue

        repository.hasBeenNotified(team).futureValue shouldBe true
      }
    }
  }

}
