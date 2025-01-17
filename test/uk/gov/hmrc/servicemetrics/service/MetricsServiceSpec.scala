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
import org.mockito.Mockito.{times, verify, when}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.play.guice.GuiceOneAppPerSuite

import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.servicemetrics.config.AppConfig
import uk.gov.hmrc.servicemetrics.connector._
import uk.gov.hmrc.servicemetrics.connector.GitHubProxyConnector.DbOverride
import uk.gov.hmrc.servicemetrics.connector.TeamsAndRepositoriesConnector.{Service, ServiceName}
import uk.gov.hmrc.servicemetrics.model.{Environment, MongoCollectionSize}
import uk.gov.hmrc.servicemetrics.persistence.{LatestMongoCollectionSizeRepository, MongoCollectionSizeHistoryRepository, LogHistoryRepository}

import java.time.LocalDate
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import java.time.Instant
import uk.gov.hmrc.servicemetrics.config.AppConfig.LogConfigType

class MetricsServiceSpec
  extends AnyWordSpec
     with Matchers
     with MockitoSugar
     with ScalaFutures
     with IntegrationPatience
     with GuiceOneAppPerSuite:

  trait Setup:
    val appConfig                                = AppConfig(app.configuration)
    val mockCarbonApiConnector                   = mock[CarbonApiConnector]
    val mockClickHouseConnector                  = mock[ClickHouseConnector]
    val mockElasticsearchConnector               = mock[ElasticsearchConnector]
    val mockTeamsAndReposConnector               = mock[TeamsAndRepositoriesConnector]
    val mockGitHubProxyConnector                 = mock[GitHubProxyConnector]
    val mockLatestMongoCollectionSizeRepository  = mock[LatestMongoCollectionSizeRepository]
    val mockMongoCollectionSizeHistoryRepository = mock[MongoCollectionSizeHistoryRepository]
    val mockLogHistoryRepository                 = mock[LogHistoryRepository]

    val service = MetricsService(
      appConfig
    , mockCarbonApiConnector
    , mockClickHouseConnector
    , mockElasticsearchConnector
    , mockTeamsAndReposConnector
    , mockGitHubProxyConnector
    , mockLatestMongoCollectionSizeRepository
    , mockMongoCollectionSizeHistoryRepository
    , mockLogHistoryRepository
    )

  given hc: HeaderCarrier = HeaderCarrier()

  "dbMappings" should:
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

      when(mockTeamsAndReposConnector.allDeletedServices()(using any[HeaderCarrier]))
        .thenReturn(Future.successful(Nil))

      when(mockGitHubProxyConnector.getMongoOverrides(any[Environment])(using any[HeaderCarrier]))
        .thenReturn(Future.successful(dbOverrides))

      val expected = Seq(
        MetricsService.DbMapping(ServiceName("service-one"         ), "service-one"         , Seq("service-one-frontend"), Seq("team-one")),
        MetricsService.DbMapping(ServiceName("service-one-frontend"), "service-one-frontend", Seq.empty                  , Seq("team-one")),
        MetricsService.DbMapping(ServiceName("service-two"         ), "service-two"         , Seq.empty                  , Seq("team-two")),
        MetricsService.DbMapping(ServiceName("service-three"       ), "random-db"           , Seq.empty                  , Seq("team-three"))
      )

      service.dbMappings(Environment.QA, knownServices).futureValue should contain theSameElementsAs expected

  "updateCollectionSizes" should:
    "insert records when none exist" in new Setup:
      val dbMapping = Seq(
        MetricsService.DbMapping(ServiceName("service"), "database", Seq("service-frontend"), Seq("team-one")),
      )

      when(mockCarbonApiConnector.getCollectionSizes(any[Environment], any[String])(using any[HeaderCarrier]))
        .thenReturn(Future.successful(
          CarbonApiConnector.MongoCollectionSizeMetric(
            metricLabel = "collection-one"
          , sizeBytes   = BigDecimal(1000)
          , timestamp   = Instant.now()
          ) :: Nil
        ))

      when(mockMongoCollectionSizeHistoryRepository.historyExists(any[Environment], any[LocalDate]))
        .thenReturn(Future.successful(false))

      when(mockLatestMongoCollectionSizeRepository.putAll(any[Seq[MongoCollectionSize]], any[Environment]))
        .thenReturn(Future.unit)

      when(mockMongoCollectionSizeHistoryRepository.insertMany(any[Seq[MongoCollectionSize]]))
        .thenReturn(Future.unit)

      val mcs = Seq(MongoCollectionSize("database", "collection-one", BigDecimal(1000), LocalDate.now(), Environment.QA, "service"))

      service.updateCollectionSizes(Environment.QA, dbMapping).futureValue

      verify(mockMongoCollectionSizeHistoryRepository, times(1)).historyExists(Environment.QA, LocalDate.now().minusDays(1))
      verify(mockLatestMongoCollectionSizeRepository , times(1)).putAll(mcs, Environment.QA)
      verify(mockMongoCollectionSizeHistoryRepository, times(1)).insertMany(mcs)

    "skip the insert if history already exists" in new Setup:
      val dbMapping = Seq(
        MetricsService.DbMapping(ServiceName("service"), "database", Seq("service-frontend"), Seq("team-one")),
      )

      when(mockCarbonApiConnector.getCollectionSizes(any[Environment], any[String])(using any[HeaderCarrier]))
        .thenReturn(Future.successful(
          CarbonApiConnector.MongoCollectionSizeMetric(
            metricLabel = "collection-one"
          , sizeBytes   = BigDecimal(1000)
          , timestamp   = Instant.now()
          ) :: Nil
        ))

      when(mockMongoCollectionSizeHistoryRepository.historyExists(any[Environment], any[LocalDate]))
        .thenReturn(Future.successful(true))

      when(mockLatestMongoCollectionSizeRepository.putAll(any[Seq[MongoCollectionSize]], any[Environment]))
        .thenReturn(Future.unit)

      val mcs = Seq(MongoCollectionSize("database", "collection-one", BigDecimal(1000), LocalDate.now(), Environment.QA, "service"))

      service.updateCollectionSizes(Environment.QA, dbMapping).futureValue

      verify(mockMongoCollectionSizeHistoryRepository, times(1)).historyExists(Environment.QA, LocalDate.now().minusDays(1))
      verify(mockLatestMongoCollectionSizeRepository , times(1)).putAll(mcs, Environment.QA)
      verify(mockMongoCollectionSizeHistoryRepository, times(0)).insertMany(mcs)

  "insertQueryLogs" should:
    "insert logs" when:
      "there are all log types" in new Setup:
        val databases = Seq("service-one")
        val knownServices = Seq(Service(ServiceName("service-one"), Seq("team-one")))

        when(mockClickHouseConnector.getDatabaseNames(any[Environment])(using any[HeaderCarrier]))
          .thenReturn(Future.successful(databases))

        when(mockGitHubProxyConnector.getMongoOverrides(any[Environment])(using any[HeaderCarrier]))
          .thenReturn(Future.successful(Seq.empty))

        when(mockElasticsearchConnector.search(any[Environment], any[String], any[String], any[Instant], any[Instant])(using any[HeaderCarrier]))
          .thenReturn(Future.successful(Seq(ElasticsearchConnector.SearchResult("some-log", 1))))

        when(mockElasticsearchConnector.averageMongoDuration(any[Environment], any[String], any[String], any[Instant], any[Instant])(using any[HeaderCarrier]))
          .thenReturn(Future.successful(Map("service-one" -> Seq(ElasticsearchConnector.AverageMongoDuration("collection-one", 3001, 1)))))

        when(mockLogHistoryRepository.insertMany(any[Seq[LogHistoryRepository.LogHistory]]))
          .thenReturn(Future.unit)

        service
          .insertLogHistory(Environment.QA, Instant.now().minusSeconds(20), Instant.now(), knownServices, service.dbMappings(Environment.QA, knownServices).futureValue)
          .futureValue shouldBe a[Unit]

        verify(mockClickHouseConnector, times(1))
          .getDatabaseNames(Environment.QA)(using hc)
        verify(mockGitHubProxyConnector, times(1))
          .getMongoOverrides(Environment.QA)(using hc)

        appConfig
          .logMetrics
          .map(_._2.logType)
          .foreach:
            case LogConfigType.GenericSearch(query)        => verify(mockElasticsearchConnector, times(1)).search              (same(Environment.QA), any[String], same(query), any[Instant], any[Instant])(using same(hc))
            case LogConfigType.AverageMongoDuration(query) => verify(mockElasticsearchConnector, times(1)).averageMongoDuration(same(Environment.QA), any[String], same(query), any[Instant], any[Instant])(using same(hc))

        verify(mockLogHistoryRepository, times(appConfig.logMetrics.size))
          .insertMany(any[Seq[LogHistoryRepository.LogHistory]])
