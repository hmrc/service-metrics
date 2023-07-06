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
import uk.gov.hmrc.servicemetrics.connector.GitHubProxyConnector.DbOverride
import uk.gov.hmrc.servicemetrics.model.{Environment, MongoCollectionSize}
import uk.gov.hmrc.servicemetrics.persistence.MongoCollectionSizeRepository
import uk.gov.hmrc.servicemetrics.service.MongoCollectionSizeService.DbMapping

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class MongoCollectionSizeServiceSpec
  extends AnyWordSpec
  with Matchers
  with MockitoSugar
  with ScalaFutures
  with IntegrationPatience {

  private val mockCarbonApiConnector     = mock[CarbonApiConnector]
  private val mockClickHouseConnector    = mock[ClickHouseConnector]
  private val mockTeamsAndReposConnector = mock[TeamsAndRepositoriesConnector]
  private val mockGitHubProxyConnector   = mock[GitHubProxyConnector]
  private val mockRepository             = mock[MongoCollectionSizeRepository]

  private val service = new MongoCollectionSizeService(
    mockCarbonApiConnector,
    mockClickHouseConnector,
    mockTeamsAndReposConnector,
    mockGitHubProxyConnector,
    mockRepository
  )

  "updateCollectionSizes" should {
    "leave the db unchanged if gathering metrics fails" in {
      when(mockClickHouseConnector.getDatabaseNames(any[Environment])(any[HeaderCarrier])).thenReturn(Future.failed(new RuntimeException("test exception")))

      implicit val hc: HeaderCarrier = HeaderCarrier()
      service.updateCollectionSizes(Environment.QA)

      verify(mockClickHouseConnector, times(1)).getDatabaseNames(any[Environment])(any[HeaderCarrier])
      verifyZeroInteractions(mockRepository.putAll(anySeq[MongoCollectionSize], any[Environment]))
    }
  }

  "getMappings" should {
    "map a database to a service taking into account overrides and similarly named dbs" in {
      val databases = Seq("service-one", "service-one-frontend", "service-two", "random-db")
      val knownServices = Seq("service-one", "service-two", "service-one-frontend", "service-three")
      val dbOverrides = Seq(DbOverride("service-three", Seq("random-db")))

      val expected = Seq(
        DbMapping("service-one", "service-one", Seq("service-one-frontend")),
        DbMapping("service-one-frontend", "service-one-frontend", Seq.empty),
        DbMapping("service-two", "service-two", Seq.empty),
        DbMapping("service-three", "random-db", Seq.empty)
      )

      service.getMappings(databases, knownServices, dbOverrides) should contain theSameElementsAs expected
    }
  }

}
