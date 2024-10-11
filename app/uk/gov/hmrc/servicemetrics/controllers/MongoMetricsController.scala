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

package uk.gov.hmrc.servicemetrics.controllers

import play.api.libs.json.{Json, Writes}
import play.api.mvc.{Action, AnyContent, ControllerComponents}
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController
import uk.gov.hmrc.servicemetrics.model.{Environment, MongoCollectionSize}
import uk.gov.hmrc.servicemetrics.persistence.{LatestMongoCollectionSizeRepository, MongoQueryLogHistoryRepository}

import javax.inject.{Inject, Singleton}
import java.time.Instant
import scala.concurrent.ExecutionContext

@Singleton()
class MongoMetricsController @Inject()(
  cc                       : ControllerComponents
, latestRepository         : LatestMongoCollectionSizeRepository
, queryLogHistoryRepository: MongoQueryLogHistoryRepository
)(using
  ExecutionContext
) extends BackendController(cc):

  def getCollections(service: String, environment: Option[Environment]): Action[AnyContent] =
    Action.async:
      given Writes[MongoCollectionSize] = MongoCollectionSize.apiWrites
      latestRepository
        .find(service, environment)
        .map(mcs => Ok(Json.toJson(mcs)))

  def nonPerformantQueriesByService(
    service    : String,
    from       : Instant,
    to         : Instant
  ): Action[AnyContent] =
    Action.async:
      given Writes[MongoQueryLogHistoryRepository.NonPerformantQueries] = MongoQueryLogHistoryRepository.NonPerformantQueries.format
      queryLogHistoryRepository
        .getQueryTypesByService(service, from, to)
        .map(nonPerformantQueries => Ok(Json.toJson(nonPerformantQueries)))
