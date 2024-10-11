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
import uk.gov.hmrc.servicemetrics.connector.TeamsAndRepositoriesConnector.ServiceName
import uk.gov.hmrc.servicemetrics.connector._
import uk.gov.hmrc.servicemetrics.connector.ElasticsearchConnector._
import uk.gov.hmrc.servicemetrics.model.{Environment, MongoCollectionSize}
import uk.gov.hmrc.servicemetrics.persistence.{LatestMongoCollectionSizeRepository, MongoCollectionSizeHistoryRepository, MongoQueryLogHistoryRepository}

import java.time.{Instant, LocalDate, ZoneOffset}
import java.time.temporal.ChronoUnit
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.fromNow
import uk.gov.hmrc.servicemetrics.connector.TeamsAndRepositoriesConnector.Service

@Singleton
class MongoMetricsService @Inject()(
  carbonApiConnector            : CarbonApiConnector
, clickHouseConnector           : ClickHouseConnector
, elasticsearchConnector        : ElasticsearchConnector
, teamsAndRepositoriesConnector : TeamsAndRepositoriesConnector
, gitHubProxyConnector          : GitHubProxyConnector
, latestRepository              : LatestMongoCollectionSizeRepository
, historyRepository             : MongoCollectionSizeHistoryRepository
, queryLogHistoryRepository     : MongoQueryLogHistoryRepository
, appConfig                     : AppConfig
)(using
  ExecutionContext
):
  private val logger = Logger(getClass)

  def getAllQueriesGroupedByTeam(
    environment: Environment,
    from       : Instant,
    to         : Instant,
  ): Future[Map[String, Seq[MongoQueryLogHistoryRepository.MongoQueryLogHistory]]] =
    queryLogHistoryRepository
      .getAll(environment, from, to)
      .map:
        _.foldLeft(Map[String, Seq[MongoQueryLogHistoryRepository.MongoQueryLogHistory]]()): (acc, mongoQueryLogHistory) =>
          mongoQueryLogHistory.teams.foldLeft(acc): (accTeamQueries, team) =>
            accTeamQueries.updatedWith(team):
              _.fold(Some(Seq(mongoQueryLogHistory)))(f => Some(f :+ mongoQueryLogHistory))

  def dbMappings(environment: Environment)(using HeaderCarrier): Future[Seq[MongoMetricsService.DbMapping]] =
    for
      databases     <- clickHouseConnector.getDatabaseNames(environment)
      knownServices <- ( teamsAndRepositoriesConnector.allServices()
                       , teamsAndRepositoriesConnector.allDeletedServices()
                       ).mapN(_ ++ _)
      dbOverrides   <- gitHubProxyConnector.getMongoOverrides(environment)
      mappings      =
                       for
                         database  <- databases
                         filterOut =  databases.filter(_.startsWith(database + "-"))
                         services  =  dbOverrides.filter(_.dbs.contains(database)).toList match
                                        case Nil       => knownServices.filter(_.name.value == database)
                                        case overrides => overrides.flatMap(o => knownServices.filter(_.name.value == o.service))
                         service   <- services
                       yield MongoMetricsService.DbMapping(
                         service   = service.name,
                         teams     = service.teamNames,
                         database  = database,
                         filterOut = filterOut
                       )
    yield mappings

  def updateCollectionSizes(environment: Environment, mappings: Seq[MongoMetricsService.DbMapping])(using HeaderCarrier): Future[Unit] =
    for
      collSizes <- mappings.foldLeftM[Future, Seq[MongoCollectionSize]](Seq.empty):
                     (acc, mapping) => getCollectionSizes(mapping, environment).map(acc ++ _)
      _         <- latestRepository.putAll(collSizes, environment)
      _         <- storeHistory(collSizes, environment)
    yield logger.info(s"Successfully updated mongo collection sizes for ${environment.asString}")

  def insertQueryLogs(environment: Environment, mappings: Seq[MongoMetricsService.DbMapping])(using HeaderCarrier): Future[Unit] =
    for
      lastInsertDate  <- queryLogHistoryRepository
                          .lastInsertDate()
                          .map(_.getOrElse(Instant.now().minus(1, ChronoUnit.HOURS)))
      currentDate     =  Instant.now()
      queryLogs       <- mappings.foldLeftM[Future, Seq[MongoQueryLogHistoryRepository.MongoQueryLogHistory]](Seq.empty): (acc, mapping) =>
                           getQueryLogs(mapping, environment, lastInsertDate, currentDate).map(acc ++ _)
      _               <-
                         if   queryLogs.nonEmpty
                         then queryLogHistoryRepository.insertMany(queryLogs)
                         else Future.unit
    yield logger.info(s"Successfully added ${queryLogs.size} query logs for ${environment.asString}")

  private[service] def storeHistory(mcs: Seq[MongoCollectionSize], environment: Environment): Future[Unit] =
    for
      afterDate     <- Future.successful(LocalDate.now().minusDays(appConfig.collectionSizesHistoryFrequencyDays))
      alreadyStored <- historyRepository.historyExists(environment, afterDate)
      _             <- if alreadyStored then Future.unit else historyRepository.insertMany(mcs)
    yield ()

  private[service] def getCollectionSizes(
    mapping    : MongoMetricsService.DbMapping
  , environment: Environment
  )(using HeaderCarrier): Future[Seq[MongoCollectionSize]] =
    carbonApiConnector
      .getCollectionSizes(environment, mapping.database)
      .map: metrics =>
        metrics
          .filterNot: metric =>
            mapping.filterOut.exists: filter =>
              metric.metricLabel.startsWith(s"mongo-$filter")
          .map: metric =>
            MongoCollectionSize(
              database    = mapping.database,
              collection  = metric.metricLabel.stripPrefix(s"mongo-${mapping.database}-"),
              sizeBytes   = metric.sizeBytes,
              date        = metric.timestamp.atZone(ZoneOffset.UTC).toLocalDate,
              environment = environment,
              service     = mapping.service.value
            )

  private[service] def getQueryLogs(
    mapping    : MongoMetricsService.DbMapping
  , environment: Environment
  , from       : Instant
  , to         : Instant
  )(using HeaderCarrier): Future[Seq[MongoQueryLogHistoryRepository.MongoQueryLogHistory]] =
    for
      slowQueries       <- elasticsearchConnector
                            .getSlowQueries(environment, mapping.database, from, to)
                            .map(toLogHistory(from, to, mapping.database, MongoQueryLogHistoryRepository.MongoQueryType.SlowQuery, mapping.service, environment, mapping.teams))
      nonIndexedQueries <- elasticsearchConnector
                            .getNonIndexedQueries(environment, mapping.database, from, to)
                            .map(toLogHistory(from, to, mapping.database, MongoQueryLogHistoryRepository.MongoQueryType.NonIndexedQuery, mapping.service, environment, mapping.teams))
    yield Seq(slowQueries, nonIndexedQueries)

  private def toLogHistory(
    from       : Instant,
    to         : Instant,
    database   : String,
    queryType  : MongoQueryLogHistoryRepository.MongoQueryType,
    service    : ServiceName,
    environment: Environment,
    teams      : Seq[String]
  )(logs: Seq[MongoCollectionNonPerformantQuery]): MongoQueryLogHistoryRepository.MongoQueryLogHistory =
    MongoQueryLogHistoryRepository.MongoQueryLogHistory(
      timestamp   = from,
      since       = to,
      database    = database,
      service     = service.value,
      queryType   = queryType,
      details     = logs.map: npq =>
                      MongoQueryLogHistoryRepository.NonPerformantQueryDetails(
                        collection  = npq.collection,
                        duration    = npq.avgDuration,
                        occurrences = npq.occurrences,
                      ),
      environment = environment,
      teams       = teams
    )

object MongoMetricsService:
  /** @param filterOut is used to filter metrics later, when querying metrics endpoint for a db
    * we can get some results which are for other dbs eg. searching for db called
    * "service-one" will bring back dbs/collections belonging to "service-one-frontend"
    */
  case class DbMapping(
    service  : ServiceName,
    database : String,
    filterOut: Seq[String],
    teams    : Seq[String]
  )
