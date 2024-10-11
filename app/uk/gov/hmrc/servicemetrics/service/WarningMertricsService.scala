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

@Singleton
class WarningMetricsService @Inject()(
  elasticsearchConnector        : ElasticsearchConnector
)(using
  ExecutionContext
):
  private val logger = Logger(getClass)

  def dbMappings(environment: Environment, from: Instant, to: Instant)(using HeaderCarrier): Future[Unit] =
    for
      search <-  elasticsearchConnector.search(environment, "tags:\"UnsafeContent\"", from, to)
    yield
      ()

