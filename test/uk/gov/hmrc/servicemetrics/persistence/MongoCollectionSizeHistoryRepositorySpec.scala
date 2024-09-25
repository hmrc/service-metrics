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
import uk.gov.hmrc.servicemetrics.model.{Environment, MongoCollectionSize}

import java.time.LocalDate
import scala.concurrent.ExecutionContext.Implicits.global

class MongoCollectionSizeHistoryRepositorySpec
  extends AnyWordSpec
  with Matchers
  with DefaultPlayMongoRepositorySupport[MongoCollectionSize] {

  override val repository: MongoCollectionSizeHistoryRepository =
    new MongoCollectionSizeHistoryRepository(mongoComponent)

  private def seed(env: Environment) = Seq(
    MongoCollectionSize("service-one", "collection-one", BigDecimal(1000), LocalDate.now(), env, "service-one")
  )

  "insertMany" should {
    "insert the records" in {

      repository.insertMany(seed(Environment.QA)).futureValue

      repository.find()
        .futureValue
        .length shouldBe 1
    }
  }

  "find" should {
    "find by date" in {
      repository.insertMany(seed(Environment.QA)).futureValue
      repository.insertMany(seed(Environment.QA).map(_.copy(date = LocalDate.now().minusDays(1)))).futureValue

      repository.find()
        .futureValue
        .length shouldBe 2

      repository.find(date = Some(LocalDate.now()))
        .futureValue
        .length shouldBe 1
    }
  }

  "historyExists" should {
    "return false when no history" in {
      repository
        .historyExists(Environment.QA, LocalDate.now().minusDays(1))
        .futureValue shouldBe false
    }

    "return true when there are records for the given environment after the cutoff date" in {
      repository.insertMany(seed(Environment.QA)).futureValue

      repository
        .historyExists(Environment.QA, LocalDate.now().minusDays(1))
        .futureValue shouldBe true
    }
  }
}
