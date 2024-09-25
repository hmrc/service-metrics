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

import org.mockito.ArgumentMatchers.{any, same}
import org.mockito.Mockito.{times, verify, verifyNoInteractions, when}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatestplus.mockito.MockitoSugar
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.servicemetrics.config.AppConfig
import uk.gov.hmrc.servicemetrics.connector._
import uk.gov.hmrc.servicemetrics.connector.GitHubProxyConnector.DbOverride
import uk.gov.hmrc.servicemetrics.connector.TeamsAndRepositoriesConnector.{Service, ServiceName}
import uk.gov.hmrc.servicemetrics.model.{Environment, MongoCollectionSize}
import uk.gov.hmrc.servicemetrics.persistence.{LatestMongoCollectionSizeRepository, MongoCollectionSizeHistoryRepository, MongoQueryLogHistoryRepository, MongoQueryNotificationRepository}
import uk.gov.hmrc.servicemetrics.service.MongoService.DbMapping

import java.time.LocalDate
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import java.time.Instant

class MongoServiceSpec
  extends AnyWordSpec
     with Matchers
     with MockitoSugar
     with ScalaFutures
     with IntegrationPatience:

  trait Setup:
    val mockCarbonApiConnector          = mock[CarbonApiConnector]
    val mockClickHouseConnector         = mock[ClickHouseConnector]
    val mockElasticsearchConnector      = mock[ElasticsearchConnector]
    val mockTeamsAndReposConnector      = mock[TeamsAndRepositoriesConnector]
    val mockGitHubProxyConnector        = mock[GitHubProxyConnector]
    val mockLatestRepository            = mock[LatestMongoCollectionSizeRepository]
    val mockHistoryRepository           = mock[MongoCollectionSizeHistoryRepository]
    val mockQueryLogHistoryRepository   = mock[MongoQueryLogHistoryRepository]
    val mockQueryNotificationRepository = mock[MongoQueryNotificationRepository]
    val mockAppConfig                   = mock[AppConfig]

    val service = MongoService(
      mockCarbonApiConnector,
      mockClickHouseConnector,
      mockElasticsearchConnector,
      mockTeamsAndReposConnector,
      mockGitHubProxyConnector,
      mockLatestRepository,
      mockHistoryRepository,
      mockQueryLogHistoryRepository,
      mockQueryNotificationRepository,
      mockAppConfig
    )

  given hc: HeaderCarrier = HeaderCarrier()

  "updateCollectionSizes" should:
    "leave the db unchanged if gathering metrics fails" in new Setup:
      when(mockClickHouseConnector.getDatabaseNames(any[Environment])(using any[HeaderCarrier]))
        .thenReturn(Future.failed(RuntimeException("test exception")))

      service.updateCollectionSizes(Environment.QA).failed.futureValue shouldBe a[RuntimeException]

      verify(mockClickHouseConnector, times(1)).getDatabaseNames(Environment.QA)(using hc)
      verifyNoInteractions(mockLatestRepository)

  "insertQueryLogs" should:
    "insert mongodb logs" when:
      "there are slow queries in ES" in new Setup:
        val databases = Seq("service-one")
        val knownServices = Seq(Service(ServiceName("service-one"), Seq("team-one")))

        when(mockClickHouseConnector.getDatabaseNames(any[Environment])(using any[HeaderCarrier]))
          .thenReturn(Future.successful(databases))

        when(mockTeamsAndReposConnector.allServices()(using any[HeaderCarrier]))
          .thenReturn(Future.successful(knownServices))

        when(mockTeamsAndReposConnector.allDeletedServices()(using any[HeaderCarrier]))
          .thenReturn(Future.successful(Nil))

        when(mockGitHubProxyConnector.getMongoOverrides(any[Environment])(using any[HeaderCarrier]))
          .thenReturn(Future.successful(Seq.empty))

        when(mockElasticsearchConnector.getSlowQueries(any[Environment], any[String], any[Instant], any[Instant])(using any[HeaderCarrier]))
          .thenReturn(Future.successful(Some(
            ElasticsearchConnector.MongoQueryLog(
              java.time.Instant.now,
              java.time.Instant.now,
              Seq(ElasticsearchConnector.MongoCollectionNonPerfomantQuery(
                "collection",
                3001,
                1,
              )),
              "database",
            )
          )))

        when(mockElasticsearchConnector.getNonIndexedQueries(any[Environment], any[String], any[Instant], any[Instant])(using any[HeaderCarrier]))
          .thenReturn(Future.successful(None))

        when(mockQueryLogHistoryRepository.insertMany(any[Seq[MongoQueryLogHistoryRepository.MongoQueryLogHistory]]))
          .thenReturn(Future.unit)

        when(mockQueryLogHistoryRepository.lastInsertDate())
          .thenReturn(Future.successful(Some(Instant.now())))

        service.insertQueryLogs(Environment.QA).futureValue shouldBe a[Unit]

        verify(mockClickHouseConnector, times(1))
          .getDatabaseNames(Environment.QA)(using hc)
        verify(mockTeamsAndReposConnector, times(1))
          .allServices()(using hc)
        verify(mockTeamsAndReposConnector, times(1))
          .allDeletedServices()(using hc)
        verify(mockGitHubProxyConnector, times(1))
          .getMongoOverrides(Environment.QA)(using hc)
        verify(mockElasticsearchConnector, times(1))
          .getSlowQueries(same(Environment.QA), same("service-one"), any[Instant], any[Instant])(using same(hc)) // TODO why `same`?
        verify(mockElasticsearchConnector, times(1))
          .getNonIndexedQueries(same(Environment.QA), same("service-one"), any[Instant], any[Instant])(using same(hc))
        verify(mockQueryLogHistoryRepository, times(1))
          .insertMany(any[Seq[MongoQueryLogHistoryRepository.MongoQueryLogHistory]])

  "storeHistory" should:
    "insert records when none exist" in new Setup:
      when(mockAppConfig.collectionSizesHistoryFrequencyDays)
        .thenReturn(1)

      when(mockHistoryRepository.historyExists(any[Environment], any[LocalDate]))
        .thenReturn(Future.successful(false))

      when(mockHistoryRepository.insertMany(any[Seq[MongoCollectionSize]]))
        .thenReturn(Future.unit)

      val mcs = Seq(MongoCollectionSize("database", "collection", BigDecimal(1000), LocalDate.now(), Environment.QA, "service"))

      service.storeHistory(mcs, Environment.QA).futureValue

      verify(mockHistoryRepository, times(1)).historyExists(Environment.QA, LocalDate.now().minusDays(1))
      verify(mockHistoryRepository, times(1)).insertMany(mcs)

    "skip the insert if history already exists" in new Setup:
      when(mockAppConfig.collectionSizesHistoryFrequencyDays)
        .thenReturn(1)

      when(mockHistoryRepository.historyExists(any[Environment], any[LocalDate]))
        .thenReturn(Future.successful(true))

      val mcs = Seq(MongoCollectionSize("database", "collection", BigDecimal(1000), LocalDate.now(), Environment.QA, "service"))

      service.storeHistory(mcs, Environment.QA).futureValue

      verify(mockHistoryRepository, times(1)).historyExists(Environment.QA, LocalDate.now().minusDays(1))
      verify(mockHistoryRepository, times(0)).insertMany(mcs)

  "getMappings" should:
    "map a database to a service taking into account overrides and similarly named dbs" in new Setup:
      val databases = Seq("service-one", "service-one-frontend", "service-two", "random-db")
      val knownServices = Seq(
        Service(ServiceName("service-one"         ), Seq("team-one")),
        Service(ServiceName("service-two"         ), Seq("team-two")),
        Service(ServiceName("service-one-frontend"), Seq("team-one")),
        Service(ServiceName("service-three"       ), Seq("team-three"))
      )
      val dbOverrides = Seq(DbOverride("service-three", Seq("random-db")))

      when(mockClickHouseConnector.getDatabaseNames(any[Environment])(using any[HeaderCarrier]))
        .thenReturn(Future.successful(databases))

      when(mockTeamsAndReposConnector.allServices()(using any[HeaderCarrier]))
        .thenReturn(Future.successful(knownServices))

      when(mockTeamsAndReposConnector.allDeletedServices()(using any[HeaderCarrier]))
        .thenReturn(Future.successful(Nil))

      when(mockGitHubProxyConnector.getMongoOverrides(any[Environment])(using any[HeaderCarrier]))
        .thenReturn(Future.successful(dbOverrides))

      val expected = Seq(
        DbMapping(ServiceName("service-one"         ), "service-one"         , Seq("service-one-frontend"), Seq("team-one")),
        DbMapping(ServiceName("service-one-frontend"), "service-one-frontend", Seq.empty                  , Seq("team-one")),
        DbMapping(ServiceName("service-two"         ), "service-two"         , Seq.empty                  , Seq("team-two")),
        DbMapping(ServiceName("service-three"       ), "random-db"           , Seq.empty                  , Seq("team-three"))
      )

      service.getMappings(Environment.QA).futureValue should contain theSameElementsAs expected
