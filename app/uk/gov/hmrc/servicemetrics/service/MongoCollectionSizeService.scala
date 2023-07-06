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

import play.api.Logger
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.servicemetrics.connector.GitHubProxyConnector.DbOverride
import uk.gov.hmrc.servicemetrics.connector.{CarbonApiConnector, ClickHouseConnector, GitHubProxyConnector, TeamsAndRepositoriesConnector}
import uk.gov.hmrc.servicemetrics.model.{Environment, MongoCollectionSize}
import uk.gov.hmrc.servicemetrics.persistence.MongoCollectionSizeRepository
import uk.gov.hmrc.servicemetrics.service.MongoCollectionSizeService.DbMapping

import java.time.ZoneOffset
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class MongoCollectionSizeService @Inject()(
  carbonApiConnector            : CarbonApiConnector
, clickHouseConnector           : ClickHouseConnector
, teamsAndRepositoriesConnector : TeamsAndRepositoriesConnector
, gitHubProxyConnector          : GitHubProxyConnector
, mongoCollectionSizeRepository : MongoCollectionSizeRepository
)(implicit
  ec: ExecutionContext
) {

  private val logger = Logger(getClass)

  def getCollections(service: String, environment: Option[Environment]): Future[Seq[MongoCollectionSize]] =
    mongoCollectionSizeRepository.find(service, environment)

  def updateCollectionSizes(environment: Environment)(implicit hc: HeaderCarrier): Future[Unit] = {
    for {
      databases   <- clickHouseConnector.getDatabaseNames(environment)
      services    <- teamsAndRepositoriesConnector.allServices()
      dbOverrides <- gitHubProxyConnector.getMongoOverrides(environment)
      mappings    =  getMappings(databases, services.map(_.value), dbOverrides)
      collSizes   <- Future.traverse(mappings)(mapping => getCollectionSizes(mapping, environment)).map(_.flatten)
      _           <- mongoCollectionSizeRepository.putAll(collSizes, environment)
    } yield logger.info(s"Successfully updated mongo collection sizes for ${environment.asString}")
  }

  private[service] def getCollectionSizes(
    mapping    : DbMapping
  , environment: Environment
  )(implicit hc: HeaderCarrier): Future[Seq[MongoCollectionSize]] =
    carbonApiConnector
      .getCollectionSizes(environment, mapping.database)
      .map { metrics =>
        metrics.filterNot(metric =>
          mapping.filterOut.exists(filter =>
            metric.metricLabel.startsWith(s"mongo-$filter")
          )
        ).map { metric =>
          MongoCollectionSize(
            database    = mapping.database,
            collection  = metric.metricLabel.stripPrefix(s"mongo-${mapping.database}-"),
            sizeBytes   = metric.sizeBytes,
            date        = metric.timestamp.atZone(ZoneOffset.UTC).toLocalDate,
            environment = environment,
            service     = mapping.service
          )
        }
      }

  private[service] def getMappings(
    databases: Seq[String]
  , knownServices: Seq[String]
  , dbOverrides: Seq[DbOverride]
  ): Seq[DbMapping] =
    for {
      database  <- databases
      filterOut =  databases.filter(_.startsWith(database + "-"))
      services  =  dbOverrides.filter(_.dbs.contains(database)).toList match {
                     case Nil => knownServices.filter(_ == database)
                     case List(o) => knownServices.filter(_ == o.service)
                     case overrides => overrides.flatMap(o => knownServices.filter(_ == o.service))
                   }
      service   <- services
    } yield DbMapping(
      service   = service,
      database  = database,
      filterOut = filterOut
    )
}

object MongoCollectionSizeService {
  // filterOut is used to filter metrics later, when querying metrics endpoint for a db
  // we can get some results which are for other dbs eg. searching for db called
  // "service-one" will bring back dbs/collections belonging to "service-one-frontend"
  final case class DbMapping(service: String, database: String, filterOut: Seq[String])
}
