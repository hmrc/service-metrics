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

import cats.implicits._
import play.api.Logger
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.servicemetrics.config.AppConfig
import uk.gov.hmrc.servicemetrics.connector._
import uk.gov.hmrc.servicemetrics.connector.ElasticsearchConnector._
import uk.gov.hmrc.servicemetrics.model.{Environment, MongoCollectionSize}
import uk.gov.hmrc.servicemetrics.persistence.{LatestMongoCollectionSizeRepository, MongoCollectionSizeHistoryRepository, LogHistoryRepository, ServiceProvisionRepository}

import java.time.{Instant, LocalDate, ZoneOffset}
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import uk.gov.hmrc.servicemetrics.connector.TeamsAndRepositoriesConnector.Service

@Singleton
class MetricsService @Inject()(
  appConfig                           : AppConfig
, carbonApiConnector                  : CarbonApiConnector
, clickHouseConnector                 : ClickHouseConnector
, elasticsearchConnector              : ElasticsearchConnector
, teamsAndRepositoriesConnector       : TeamsAndRepositoriesConnector
, gitHubProxyConnector                : GitHubProxyConnector
, latestMongoCollectionSizeRepository : LatestMongoCollectionSizeRepository
, mongoCollectionSizeHistoryRepository: MongoCollectionSizeHistoryRepository
, logHistoryRepository                : LogHistoryRepository
, serviceProvisionRepository          : ServiceProvisionRepository
)(using
  ExecutionContext
):
  private val logger = Logger(getClass)

  def teamLogs(from: Instant, to: Instant): Future[Map[String, Seq[LogHistoryRepository.LogHistory]]] =
    logHistoryRepository
      .find(from = from, to = to)
      .map:
        _.foldLeft(Map[String, Seq[LogHistoryRepository.LogHistory]]()): (acc, logs) =>
          logs.teams.foldLeft(acc): (accTeamQueries, team) =>
            accTeamQueries.updatedWith(team):
              _.fold(Some(Seq(logs)))(f => Some(f :+ logs))

  def knownServices()(using HeaderCarrier): Future[Seq[Service]] =
    ( teamsAndRepositoriesConnector.allServices()
    , teamsAndRepositoriesConnector.allDeletedServices()
    ).mapN(_ ++ _)

  def dbMappings(environment: Environment, knownServices: Seq[Service])(using HeaderCarrier): Future[Seq[MetricsService.DbMapping]] =
    for
      databases     <- clickHouseConnector.getDatabaseNames(environment)
      dbOverrides   <- gitHubProxyConnector.getMongoOverrides(environment)
      mappings      =
                       for
                         database  <- databases
                         filterOut =  databases.filter(_.startsWith(database + "-"))
                         services  =  dbOverrides.filter(_.dbs.contains(database)).toList match
                                        case Nil       => knownServices.filter(_.name == database)
                                        case overrides => overrides.flatMap(o => knownServices.filter(_.name == o.service))
                         service   <- services
                       yield MetricsService.DbMapping(
                         service   = service.name,
                         teams     = service.teamNames,
                         database  = database,
                         filterOut = filterOut
                       )
    yield mappings

  def updateCollectionSizes(environment: Environment, from: Instant, to: Instant, mappings: Seq[MetricsService.DbMapping])(using HeaderCarrier): Future[Unit] =
    for
      collSizes <- mappings.foldLeftM[Future, Seq[MongoCollectionSize]](Seq.empty): (acc, mapping) =>
                    carbonApiConnector
                      .getCollectionSizes(environment = environment, database = mapping.database, from = from, to = to)
                      .map: metrics =>
                        metrics
                          .filterNot(metric => mapping.filterOut.exists(filter => metric.label.startsWith(s"mongo-$filter")))
                          .map: metric =>
                            MongoCollectionSize(
                              database    = mapping.database,
                              collection  = metric.label.stripPrefix(s"mongo-${mapping.database}-"),
                              sizeBytes   = metric.value,
                              date        = metric.timestamp.atZone(ZoneOffset.UTC).toLocalDate,
                              environment = environment,
                              service     = mapping.service
                            )
                      .map(acc ++ _)
      _         <- latestMongoCollectionSizeRepository.putAll(collSizes, environment)
      afterDate =  LocalDate.now().minusDays(appConfig.collectionSizesHistoryFrequency.toDays)
      iPresent  <- mongoCollectionSizeHistoryRepository.historyExists(environment, afterDate)
      _         <- if   iPresent
                   then Future.unit
                   else mongoCollectionSizeHistoryRepository.insertMany(collSizes)
    yield logger.info(s"Successfully updated mongo collection sizes for ${environment.asString}")

  def insertLogHistory(environment: Environment, from: Instant, to: Instant, knownServices: Seq[Service], dbMappings: Seq[MetricsService.DbMapping])(using HeaderCarrier): Future[Unit] =
    appConfig.logMetrics.toSeq.foldLeftM(()):
      case (_, (logMetricId, logMetric)) =>
        for
          logs <- logMetric.logType match
                    case AppConfig.LogConfigType.GenericSearch(query) =>
                      elasticsearchConnector
                        .search(environment, logMetric.dataView, query, from, to, logMetric.keyword)
                        .map: logs =>
                          logs.map: res =>
                            LogHistoryRepository.LogHistory(
                              timestamp   = from
                            , since       = to
                            , service     = res.key
                            , logType     = LogHistoryRepository.LogType.GenericSearch(logMetricId, res.count)
                            , environment = environment
                            , teams       = knownServices.collect { case repo if repo.name == res.key => repo.teamNames }.flatten.distinct
                            )
                    case AppConfig.LogConfigType.AverageMongoDuration(query) =>
                      elasticsearchConnector
                        .averageMongoDuration(environment, logMetric.dataView, query, from, to)
                        .map: logs =>
                          logs
                            .map: (database, collections) =>
                              (dbMappings.find(_.database == database), database, collections)
                            .flatMap:
                              case (None           , database, collections) =>
                                logger.warn(s"Could not find service for db $database with collections ${collections.map(_.collection).mkString(", ") }")
                                Nil
                              case (Some(dbMapping), database, collections) =>
                                LogHistoryRepository.LogHistory(
                                  timestamp   = from
                                , since       = to
                                , service     = dbMapping.service
                                , logType     = LogHistoryRepository.LogType.AverageMongoDuration(
                                                  logMetricId = logMetricId
                                                , details     = collections.map: res =>
                                                                  LogHistoryRepository.LogType.AverageMongoDuration.MongoDetails(
                                                                    database    = database
                                                                  , collection  = res.collection
                                                                  , duration    = res.avgDuration
                                                                  , occurrences = res.occurrences
                                                                  )
                                                )
                                , environment = environment
                                , teams       = dbMapping.teams
                                ) :: Nil
                            .toSeq
          _    <-
                  if   logs.nonEmpty
                  then logHistoryRepository.insertMany(logs)
                  else Future.unit
        yield logger.info(s"Successfully added ${logs.size} ${logMetricId} logs for ${environment.asString}")

  def insertServiceProvisionMetrics(environment: Environment, from: Instant, to: Instant, services: Seq[String])(using HeaderCarrier): Future[Unit] =
    for
      metrics <- services.foldLeftM[Future, Seq[ServiceProvisionRepository.ServiceProvision]](Seq.empty): (acc, service) =>
                   carbonApiConnector
                     .getServiceProvisionMetrics(environment, service, from = from, to = to)
                     .map: xs =>
                       acc :+ ServiceProvisionRepository.ServiceProvision(
                         from        = from
                       , to          = to
                       , service     = service
                       , environment = environment
                       , metrics     = xs.map(x => x.label -> x.value).toMap
                       )
      _       <- serviceProvisionRepository.insertMany(environment, from = from, to = to, metrics)
    yield logger.info(s"Successfully inserted service provision metrics for ${environment.asString}")

object MetricsService:
  /** @param filterOut is used to filter metrics later, when querying metrics endpoint for a db
    * we can get some results which are for other dbs eg. searching for db called
    * "service-one" will bring back dbs/collections belonging to "service-one-frontend"
    */
  case class DbMapping(
    service  : String,
    database : String,
    filterOut: Seq[String],
    teams    : Seq[String]
  )
