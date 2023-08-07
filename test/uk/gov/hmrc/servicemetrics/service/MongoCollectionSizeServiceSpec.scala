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
import uk.gov.hmrc.servicemetrics.connector.{CarbonApiConnector, ClickHouseConnector, GitHubProxyConnector, TeamsAndRepositoriesConnector}
import org.mockito.scalatest.MockitoSugar
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.servicemetrics.config.AppConfig
import uk.gov.hmrc.servicemetrics.connector.GitHubProxyConnector.DbOverride
import uk.gov.hmrc.servicemetrics.connector.TeamsAndRepositoriesConnector.ServiceName
import uk.gov.hmrc.servicemetrics.model.{Environment, MongoCollectionSize}
import uk.gov.hmrc.servicemetrics.persistence.{LatestMongoCollectionSizeRepository, MongoCollectionSizeHistoryRepository}
import uk.gov.hmrc.servicemetrics.service.MongoCollectionSizeService.DbMapping

import java.time.LocalDate
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class MongoCollectionSizeServiceSpec
  extends AnyWordSpec
  with Matchers
  with MockitoSugar
  with ScalaFutures
  with IntegrationPatience {

  trait Setup {
    val mockCarbonApiConnector     = mock[CarbonApiConnector]
    val mockClickHouseConnector    = mock[ClickHouseConnector]
    val mockTeamsAndReposConnector = mock[TeamsAndRepositoriesConnector]
    val mockGitHubProxyConnector   = mock[GitHubProxyConnector]
    val mockLatestRepository       = mock[LatestMongoCollectionSizeRepository]
    val mockHistoryRepository      = mock[MongoCollectionSizeHistoryRepository]
    val mockAppConfig              = mock[AppConfig]

    val service = new MongoCollectionSizeService(
      mockCarbonApiConnector,
      mockClickHouseConnector,
      mockTeamsAndReposConnector,
      mockGitHubProxyConnector,
      mockLatestRepository,
      mockHistoryRepository,
      mockAppConfig
    )
  }

  "updateCollectionSizes" should {
    "leave the db unchanged if gathering metrics fails" in new Setup {
      when(mockClickHouseConnector.getDatabaseNames(any[Environment])(any[HeaderCarrier])).thenReturn(Future.failed(new RuntimeException("test exception")))

      implicit val hc: HeaderCarrier = HeaderCarrier()
      service.updateCollectionSizes(Environment.QA).failed.futureValue shouldBe a[RuntimeException]

      verify(mockClickHouseConnector, times(1)).getDatabaseNames(Environment.QA)(hc)
      verifyZeroInteractions(mockLatestRepository)
    }
  }

  "storeHistory" should {
    "insert records when none exist" in new Setup {
      when(mockAppConfig.collectionSizesHistoryFrequencyDays).thenReturn(1)

      when(mockHistoryRepository.historyExists(any[Environment], any[LocalDate]))
        .thenReturn(Future.successful(false))

      when(mockHistoryRepository.insertMany(anySeq[MongoCollectionSize]))
        .thenReturn(Future.unit)

      val mcs = Seq(MongoCollectionSize("database", "collection", BigDecimal(1000), LocalDate.now(), Environment.QA, "service"))

      service.storeHistory(mcs, Environment.QA).futureValue

      verify(mockHistoryRepository, times(1)).historyExists(Environment.QA, LocalDate.now().minusDays(1))
      verify(mockHistoryRepository, times(1)).insertMany(mcs)
    }

    "skip the insert if history already exists" in new Setup {
      when(mockAppConfig.collectionSizesHistoryFrequencyDays).thenReturn(1)

      when(mockHistoryRepository.historyExists(any[Environment], any[LocalDate]))
        .thenReturn(Future.successful(true))

      val mcs = Seq(MongoCollectionSize("database", "collection", BigDecimal(1000), LocalDate.now(), Environment.QA, "service"))

      service.storeHistory(mcs, Environment.QA).futureValue

      verify(mockHistoryRepository, times(1)).historyExists(Environment.QA, LocalDate.now().minusDays(1))
      verify(mockHistoryRepository, times(0)).insertMany(mcs)
    }
  }

  "getMappings" should {
    "map a database to a service taking into account overrides and similarly named dbs" in new Setup {
      val databases = Seq("service-one", "service-one-frontend", "service-two", "random-db")
      val knownServices = Seq("service-one", "service-two", "service-one-frontend", "service-three").map(ServiceName.apply)
      val dbOverrides = Seq(DbOverride("service-three", Seq("random-db")))

      val expected = Seq(
        DbMapping(ServiceName("service-one"), "service-one", Seq("service-one-frontend")),
        DbMapping(ServiceName("service-one-frontend"), "service-one-frontend", Seq.empty),
        DbMapping(ServiceName("service-two"), "service-two", Seq.empty),
        DbMapping(ServiceName("service-three"), "random-db", Seq.empty)
      )

      service.getMappings(databases, knownServices, dbOverrides) should contain theSameElementsAs expected
    }
  }

}
