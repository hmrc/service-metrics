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

import play.api.libs.json.{Json, Writes, __}
import play.api.mvc.{Action, AnyContent, ControllerComponents}
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController
import uk.gov.hmrc.servicemetrics.model.{Environment, MongoCollectionSize}
import uk.gov.hmrc.servicemetrics.persistence.{LatestMongoCollectionSizeRepository, LogHistoryRepository}

import javax.inject.{Inject, Singleton}
import java.time.Instant
import scala.concurrent.ExecutionContext

@Singleton()
class MongoMetricsController @Inject()(
  cc                                 : ControllerComponents
, latestMongoCollectionSizeRepository: LatestMongoCollectionSizeRepository
, logHistoryRepository               : LogHistoryRepository
)(using
  ExecutionContext
) extends BackendController(cc):

  def getCollections(service: String, environment: Option[Environment]): Action[AnyContent] =
    Action.async:
      given Writes[MongoCollectionSize] = MongoCollectionSize.apiWrites
      latestMongoCollectionSizeRepository
        .find(service, environment)
        .map(xs => Ok(Json.toJson(xs)))

  def getLogMetrics(
    service    : String
  , from       : Instant
  , to         : Instant
  ): Action[AnyContent] =
    Action.async:
      given Writes[MongoMetricsController.LogMetric] = MongoMetricsController.LogMetric.write
      logHistoryRepository
        .find(service = Some(service), from = from, to = to)
        .map:
          _.groupBy(_.environment)
           .map:
            case (environment, results) =>
              MongoMetricsController.LogMetric(
                service,
                environment,
                results.map(_.logType).distinct
              )
        .map(xs => Ok(Json.toJson(xs)))

object MongoMetricsController:
  case class LogMetric(
    service    : String,
    environment: Environment,
    queryTypes : Seq[LogHistoryRepository.LogType]
  )

  object LogMetric:
    import play.api.libs.functional.syntax._

    val write: Writes[LogMetric] =
      given Writes[LogHistoryRepository.LogType] = LogHistoryRepository.LogType.format
      ( (__ \ "service"    ).write[String]
      ~ (__ \ "environment").write[Environment]
      ~ (__ \ "queryTypes" ).write[Seq[LogHistoryRepository.LogType]]
      )(pt => Tuple.fromProductTyped(pt))
