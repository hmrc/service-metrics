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
import uk.gov.hmrc.servicemetrics.connector.GitHubConnector.DbOverride
import uk.gov.hmrc.servicemetrics.connector.CarbonApiConnector.MongoCollectionSizeMetric
import uk.gov.hmrc.servicemetrics.connector.{GitHubConnector, CarbonApiConnector, TeamsAndRepositoriesConnector}
import uk.gov.hmrc.servicemetrics.model.{Environment, MongoCollectionSize}
import uk.gov.hmrc.servicemetrics.persistence.MongoCollectionSizeRepository

import java.time.ZoneOffset
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class MongoMetricsService @Inject()(
  carbonApiConnector            : CarbonApiConnector
, teamsAndRepositoriesConnector : TeamsAndRepositoriesConnector
, gitHubConnector               : GitHubConnector
, mongoCollectionSizeRepository : MongoCollectionSizeRepository
)(implicit
  ec: ExecutionContext
) {

  private val logger = Logger(getClass)

  def updateCollectionSizes(environment: Environment)(implicit hc: HeaderCarrier): Future[Unit] = {
    logger.info(s"updating mongo collection sizes for ${environment.asString}")
    for {
      services    <- teamsAndRepositoriesConnector.allServices()
      databases   <- carbonApiConnector.getDatabaseNames(environment)
      dbOverrides <- gitHubConnector.getMongoOverrides()
      sorted      =  databases.map(_.value).sortBy(_.length).reverse
      metrics     <- carbonApiConnector.getMongoMetrics(environment)
      transformed =  metrics.flatMap { m =>
                       transform(environment, m, sorted, dbOverrides, services.map(_.value))
                     }.flatten
      _           <- mongoCollectionSizeRepository.putAll(transformed)
    } yield ()
  }

  private[service] def transform(
    environment     : Environment
  , metric          : MongoCollectionSizeMetric
  , dbsLongestFirst : Seq[String]
  , dbOverrides     : Seq[DbOverride]
  , knownServices   : Seq[String]
  ): Option[Seq[MongoCollectionSize]] =
    for {
      database   <- dbsLongestFirst.find(db => metric.metricLabel.startsWith(s"mongo-$db"))
      services   =  dbOverrides.filter(_.dbs.contains(database)).toList match {
                      case Nil       => Seq(knownServices.find(_.equals(database)))
                      case List(o)   => Seq(knownServices.find(_.equals(o.service)))
                      case overrides => overrides.map(o => knownServices.find(_.equals(o.service)))
                    }
      collection =  metric.metricLabel.stripPrefix(s"mongo-$database").stripPrefix("-")
      if collection.nonEmpty
    } yield services.map(service => MongoCollectionSize(
      database    = database,
      collection  = collection,
      sizeBytes   = metric.sizeBytes,
      date        = metric.timestamp.atZone(ZoneOffset.UTC).toLocalDate,
      environment = environment,
      service     = service
    ))
}
