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
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport
import uk.gov.hmrc.servicemetrics.model.Environment

import java.time.Instant
import scala.concurrent.ExecutionContext.Implicits.global

class MongoQueryLogHistoryRepositorySpec
  extends AnyWordSpec
  with Matchers
  with DefaultPlayMongoRepositorySupport[MongoQueryLogHistoryRepository.MongoQueryLogHistory] {

  override lazy val repository = new MongoQueryLogHistoryRepository(mongoComponent)

  private def seed(
      env            : Environment,
      mongoQueryTypes: Seq[MongoQueryLogHistoryRepository.MongoQueryType]
    ) = mongoQueryTypes.map(queryType =>
      MongoQueryLogHistoryRepository.MongoQueryLogHistory(
        timestamp   = Instant.now,
        since       = Instant.now.minusSeconds(20),
        database    = "database",
        details     = Seq(
          MongoQueryLogHistoryRepository.NonPerformantQueryDetails(
            occurrences = 1,
            collection  = "collection",
            duration    = 3001,
          )
        ),
        service     = "service",
        queryType   = queryType,
        environment = env,
        teams       = Seq("team")
      )
    )

  "getAll" should {
    "return results" when {
      "there are non performant queries for an environment" in {
        val environment = Environment.QA

        repository.insertMany(seed(
          environment,
          Seq(MongoQueryLogHistoryRepository.MongoQueryType.SlowQuery)
        )).futureValue


        repository.getAll(
            environment,
            Instant.now().minusSeconds(10),
            Instant.now()
          ).futureValue should not be empty
      }
    }
  }

  "getQueryTypesByService" should {
    "return results" when {
      "there are non performant queries for a service" in {
        val expectedResult = MongoQueryLogHistoryRepository.NonPerformantQueries(
          "service",
          Environment.QA,
          Seq(
            MongoQueryLogHistoryRepository.MongoQueryType.NonIndexedQuery,
            MongoQueryLogHistoryRepository.MongoQueryType.SlowQuery,
          ),
        )

        repository.insertMany(seed(expectedResult.environment, expectedResult.queryTypes)).futureValue

        repository.getQueryTypesByService(
            expectedResult.service,
            Instant.now().minusSeconds(10),
            Instant.now()
          )
          .futureValue shouldBe List(expectedResult)
      }
    }
  }
}
