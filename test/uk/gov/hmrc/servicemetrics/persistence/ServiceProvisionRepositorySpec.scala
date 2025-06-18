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
import org.mongodb.scala.ObservableFuture
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport
import uk.gov.hmrc.servicemetrics.model.Environment

import java.time.Instant
import scala.concurrent.ExecutionContext.Implicits.global

class ServiceProvisionRepositorySpec
  extends AnyWordSpec
  with Matchers
  with DefaultPlayMongoRepositorySupport[ServiceProvisionRepository.ServiceProvision]:

  override val repository: ServiceProvisionRepository =
    ServiceProvisionRepository(mongoComponent)

  private val serviceProvision =
    ServiceProvisionRepository.ServiceProvision(
      from        = Instant.parse("2025-01-01T10:15:30.00Z")
    , to          = Instant.parse("2025-02-01T10:15:30.00Z")
    , service     = "some-service"
    , environment = Environment.QA
    , metrics     = Map("requests" -> 1000, "time" -> 1, "instances" -> 2, "slots" -> 8, "memory" -> 1024)
    )

  "insertMany" should:
    "add metrics for environment and date range" in:
      (for
        _  <- repository.insertMany(
                environment = serviceProvision.environment
              , from        = serviceProvision.from
              , to          = serviceProvision.to
              , metrics     = serviceProvision :: Nil
              )
        xs <- repository.collection.find().toFuture
       yield
        xs.size shouldBe 1
      ).futureValue

    "stop metrics being added to the wrong environment" in:
      val exception =
        intercept[RuntimeException]:
          repository.insertMany(
            environment = Environment.Staging
          , from        = serviceProvision.from
          , to          = serviceProvision.to
          , metrics     = serviceProvision :: Nil
          ).futureValue

      exception shouldBe an[RuntimeException]
      exception.getMessage() shouldBe "staging does not match service provision metrics QA"

    "stop metrics being added to the wrong date range" in:
      val exception =
        intercept[RuntimeException]:
          repository.insertMany(
            environment = serviceProvision.environment
          , from        = serviceProvision.to     // testing wrong way round
          , to          = serviceProvision.from
          , metrics     = serviceProvision :: Nil
          ).futureValue

      exception shouldBe an[RuntimeException]
      exception.getMessage() shouldBe "2025-02-01T10:15:30Z - 2025-01-01T10:15:30Z does not cover service provision date ranges: 2025-01-01T10:15:30Z - 2025-02-01T10:15:30Z"
