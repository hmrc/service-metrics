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
import uk.gov.hmrc.servicemetrics.persistence.MongoQueryLogHistoryRepository._
import uk.gov.hmrc.servicemetrics.persistence.MongoQueryLogHistoryRepository.MongoQueryLogHistory
import uk.gov.hmrc.servicemetrics.persistence.MongoQueryNotificationRepository
import uk.gov.hmrc.servicemetrics.service.MongoService.DbMapping

import java.time.{Instant, LocalDate, ZoneOffset}
import java.time.temporal.ChronoUnit
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class MongoService @Inject()(
  carbonApiConnector            : CarbonApiConnector
, clickHouseConnector           : ClickHouseConnector
, elasticsearchConnector        : ElasticsearchConnector
, teamsAndRepositoriesConnector : TeamsAndRepositoriesConnector
, gitHubProxyConnector          : GitHubProxyConnector
, latestRepository              : LatestMongoCollectionSizeRepository
, historyRepository             : MongoCollectionSizeHistoryRepository
, queryLogHistoryRepository     : MongoQueryLogHistoryRepository
, queryNotificationRepository   : MongoQueryNotificationRepository
, appConfig                     : AppConfig
)(using
  ExecutionContext
):
  private val logger = Logger(getClass)

  def getCollections(service: String, environment: Option[Environment]): Future[Seq[MongoCollectionSize]] =
    latestRepository.find(service, environment)

  def updateCollectionSizes(environment: Environment)(using HeaderCarrier): Future[Unit] =
    for
      mappings    <- getMappings(environment)
      collSizes   <- mappings.foldLeftM[Future, Seq[MongoCollectionSize]](Seq.empty):
                       (acc, mapping) => getCollectionSizes(mapping, environment).map(acc ++ _)
      _           <- latestRepository.putAll(collSizes, environment)
      _           <- storeHistory(collSizes, environment)
    yield logger.info(s"Successfully updated mongo collection sizes for ${environment.asString}")

  def nonPerformantQueriesByService(
    service    : String,
    from       : Instant,
    to         : Instant,
  ): Future[Seq[NonPerformantQueries]] =
    queryLogHistoryRepository.getQueryTypesByService(service, from, to)

  def getAllQueries(
    environment: Environment,
    from       : Instant,
    to         : Instant,
  ): Future[Seq[MongoQueryLogHistory]] =
    queryLogHistoryRepository.getAll(environment, from, to)

  def getAllQueriesGroupedByTeam(
    environment: Environment,
    from       : Instant,
    to         : Instant,
  ): Future[Map[String, Seq[MongoQueryLogHistory]]] =
    queryLogHistoryRepository.getAll(environment, from, to)
      .map:
        _.foldLeft(Map[String, Seq[MongoQueryLogHistory]]()): (acc, mongoQueryLogHistory) =>
          mongoQueryLogHistory.teams.foldLeft(acc): (accTeamQueries, team) =>
            accTeamQueries.updatedWith(team):
              _.fold(Some(Seq(mongoQueryLogHistory)))(f => Some(f :+ mongoQueryLogHistory))

  def insertQueryLogs(environment: Environment)(using HeaderCarrier): Future[Unit] =
    for
      lastInsertDate  <- queryLogHistoryRepository.lastInsertDate()
                          .map(_.getOrElse(Instant.now().minus(1, ChronoUnit.HOURS)))
      currentDate     =  Instant.now()
      mappings        <- getMappings(environment)
      queryLogs       <- mappings.foldLeftM[Future, Seq[MongoQueryLogHistory]](Seq.empty): (acc, mapping) =>
                           getQueryLogs(mapping, environment, lastInsertDate, currentDate).map(acc ++ _)
      _               <-
                         if queryLogs.nonEmpty then
                           queryLogHistoryRepository.insertMany(queryLogs)
                         else
                           Future.unit
    yield logger.info(s"Successfully added query logs for ${environment.asString}")

  def hasBeenNotified(team : String): Future[Boolean] =
    queryNotificationRepository.hasBeenNotified(team)

  def flagAsNotified(notifications: Seq[MongoQueryNotificationRepository.MongoQueryNotification]): Future[Unit] =
    queryNotificationRepository.insertMany(notifications)

  private[service] def storeHistory(mcs: Seq[MongoCollectionSize], environment: Environment): Future[Unit] =
    for
      afterDate     <- Future.successful(LocalDate.now().minusDays(appConfig.collectionSizesHistoryFrequencyDays))
      alreadyStored <- historyRepository.historyExists(environment, afterDate)
      _             <- if alreadyStored then Future.unit else historyRepository.insertMany(mcs)
    yield ()

  private[service] def getCollectionSizes(
    mapping    : DbMapping
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
    mapping    : DbMapping
  , environment: Environment
  , from       : Instant
  , to         : Instant
  )(using HeaderCarrier): Future[Seq[MongoQueryLogHistory]] =
    for {
      slowQueries       <- elasticsearchConnector.getSlowQueries(environment, mapping.database, from, to)
                            .map(_.map(toLogHistory(MongoQueryType.SlowQuery, mapping.service.value, environment, mapping.teams)))
      nonIndexedQueries <- elasticsearchConnector.getNonIndexedQueries(environment, mapping.database, from, to)
                            .map(_.map(toLogHistory(MongoQueryType.NonIndexedQuery, mapping.service.value, environment, mapping.teams)))
    } yield Seq(slowQueries, nonIndexedQueries).flatten

  private def toLogHistory(
    queryType  : MongoQueryType,
    service    : String,
    environment: Environment,
    teams      : Seq[String]
  )(log: MongoQueryLog): MongoQueryLogHistory =
    MongoQueryLogHistory(
      timestamp   = log.timestamp,
      since       = log.since,
      database    = log.database,
      service     = service,
      queryType   = queryType,
      details     = log.nonPerformantQueries.map: npq =>
                      NonPerformantQueryDetails(
                        npq.collection,
                        npq.duration,
                        npq.occurrences,
                      ),
      environment = environment,
      teams       = teams
    )

  private[service] def getMappings(environment: Environment)(using HeaderCarrier): Future[Seq[DbMapping]] =
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
                       yield DbMapping(
                         service   = service.name,
                         teams     = service.teamNames,
                         database  = database,
                         filterOut = filterOut
                       )
    yield mappings

object MongoService:
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
