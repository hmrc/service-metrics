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

class LatestMongoCollectionSizeRepositorySpec
  extends AnyWordSpec
  with Matchers
  with DefaultPlayMongoRepositorySupport[MongoCollectionSize]:

  override val repository: LatestMongoCollectionSizeRepository =
    LatestMongoCollectionSizeRepository(mongoComponent)

  private def seed(env: Environment) =
    Seq(
      MongoCollectionSize("service-one", "collection-one", BigDecimal(1000), LocalDate.now(), env, "service-one")
    )

  "putAll" should:
    "refresh data for a given environment" in:
      repository.putAll(seed(Environment.QA        ), Environment.QA        ).futureValue
      repository.putAll(seed(Environment.Staging   ), Environment.Staging   ).futureValue
      repository.putAll(seed(Environment.Production), Environment.Production).futureValue

      repository.find("service-one", Some(Environment.QA))
        .futureValue
        .headOption
        .map(_.sizeBytes) shouldBe Some(BigDecimal(1000))

      val updated = seed(Environment.QA).map(_.copy(sizeBytes = BigDecimal(2000)))

      repository.putAll(updated, Environment.QA).futureValue

      repository.find("service-one", Some(Environment.Staging))
        .futureValue
        .headOption
        .map(_.sizeBytes) shouldBe Some(BigDecimal(1000))

      repository.find("service-one", Some(Environment.QA))
        .futureValue
        .headOption
        .map(_.sizeBytes) shouldBe Some(BigDecimal(2000))
