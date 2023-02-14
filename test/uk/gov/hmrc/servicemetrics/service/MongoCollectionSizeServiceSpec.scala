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

package uk.gov.hmrc.servicemetrics.service

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.hmrc.servicemetrics.connector.{CarbonApiConnector, GitHubConnector, TeamsAndRepositoriesConnector}
import org.mockito.scalatest.MockitoSugar
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.servicemetrics.connector.GitHubConnector.DbOverride
import uk.gov.hmrc.servicemetrics.connector.CarbonApiConnector.MongoCollectionSizeMetric
import uk.gov.hmrc.servicemetrics.model.{Environment, MongoCollectionSize}
import uk.gov.hmrc.servicemetrics.persistence.MongoCollectionSizeRepository

import java.time.{Instant, LocalDate}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class MongoCollectionSizeServiceSpec
  extends AnyWordSpec
  with Matchers
  with MockitoSugar
  with ScalaFutures
  with IntegrationPatience {

  private val mockTeamsAndReposConnector = mock[TeamsAndRepositoriesConnector]
  private val mockRepository             = mock[MongoCollectionSizeRepository]

  private val service = new MongoCollectionSizeService(
    mock[CarbonApiConnector],
    mockTeamsAndReposConnector,
    mock[GitHubConnector],
    mockRepository
  )

  "updateCollectionSizes" should {
    "leave the db unchanged if gathering metrics fails" in {
      when(mockTeamsAndReposConnector.allServices()(any[HeaderCarrier])).thenReturn(Future.failed(new RuntimeException("test exception")))

      implicit val hc: HeaderCarrier = HeaderCarrier()
      service.updateCollectionSizes(Environment.QA)

      verify(mockTeamsAndReposConnector, times(1)).allServices()
      verifyZeroInteractions(mockRepository.putAll(anySeq[MongoCollectionSize], any[Environment]))
    }
  }

  "transform" should {
    "return MongoCollectionSize for a service" in {
      val env             = Environment.QA
      val metric          = MongoCollectionSizeMetric("mongo-service-collection-one", BigDecimal(1000), Instant.ofEpochSecond(1675353600))
      val dbsLongestFirst = Seq("service-frontend", "service")
      val dbOverrides     = Seq.empty
      val knownServices   = Seq("service-frontend", "service")

      val expected = Seq(
        MongoCollectionSize(
          database    = "service",
          collection  = "collection-one",
          sizeBytes   = BigDecimal(1000),
          date        = LocalDate.of(2023, 2, 2),
          environment = Environment.QA,
          service     = Some("service")
        )
      )

      service.transform(env, metric, dbsLongestFirst, dbOverrides, knownServices) shouldBe Some(expected)

    }

    "return service name from override when present" in {
      val env = Environment.QA
      val metric = MongoCollectionSizeMetric("mongo-keystore-collection-one", BigDecimal(1000), Instant.ofEpochSecond(1675353600))
      val dbsLongestFirst = Seq("keystore")
      val dbOverrides     = Seq(DbOverride("key-store", Seq("keystore")))
      val knownServices   = Seq("key-store")

      val expected = Seq(
        MongoCollectionSize(
          database = "keystore",
          collection = "collection-one",
          sizeBytes = BigDecimal(1000),
          date = LocalDate.of(2023, 2, 2),
          environment = Environment.QA,
          service = Some("key-store")
        )
      )

      service.transform(env, metric, dbsLongestFirst, dbOverrides, knownServices) shouldBe Some(expected)
    }

    "return multiple results when the database is shared between two services" in {
      val env = Environment.QA
      val metric = MongoCollectionSizeMetric("mongo-shared-db-collection-one", BigDecimal(1000), Instant.ofEpochSecond(1675353600))
      val dbsLongestFirst = Seq("shared-db")
      val dbOverrides     = Seq(DbOverride("service-one", Seq("shared-db")), DbOverride("service-two", Seq("shared-db")))
      val knownServices   = Seq("service-one", "service-two")

      val expected = Seq(
        MongoCollectionSize(
          database = "shared-db",
          collection = "collection-one",
          sizeBytes = BigDecimal(1000),
          date = LocalDate.of(2023, 2, 2),
          environment = Environment.QA,
          service = Some("service-one")
        ),
        MongoCollectionSize(
          database = "shared-db",
          collection = "collection-one",
          sizeBytes = BigDecimal(1000),
          date = LocalDate.of(2023, 2, 2),
          environment = Environment.QA,
          service = Some("service-two")
        )
      )

      service.transform(env, metric, dbsLongestFirst, dbOverrides, knownServices).get should contain theSameElementsAs expected
    }

    "return None when has no collection name" in {
      val env = Environment.QA
      val metric = MongoCollectionSizeMetric("mongo-service", BigDecimal(1000), Instant.ofEpochSecond(1675353600))
      val dbsLongestFirst = Seq("service-frontend", "service")
      val dbOverrides = Seq.empty
      val knownServices = Seq("service-frontend", "service")

      service.transform(env, metric, dbsLongestFirst, dbOverrides, knownServices) shouldBe None
    }


  }

}
