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
import uk.gov.hmrc.servicemetrics.config.AppConfig
import uk.gov.hmrc.servicemetrics.model.{Environment, MongoCollectionSize}
import uk.gov.hmrc.servicemetrics.persistence.{LatestMongoCollectionSizeRepository, LogHistoryRepository}

import javax.inject.{Inject, Singleton}
import java.time.Instant
import scala.concurrent.ExecutionContext

@Singleton()
class MetricsController @Inject()(
  cc                                 : ControllerComponents
, appConfig                          : AppConfig
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

  // Return Kibana links regardless of environment log count, so can always be accessed via catalogue.
  def getLogMetrics(
    service    : String
  , from       : Instant
  , to         : Instant
  ): Action[AnyContent] =
    Action.async:
      given Writes[MetricsController.LogMetric] = MetricsController.LogMetric.write
      for
        history     <- logHistoryRepository.find(service = Some(service), from = from, to = to)
        collections <- latestMongoCollectionSizeRepository.find(service)
        results     =  appConfig.logMetrics.map: logMetric =>
                        MetricsController.LogMetric(
                          id           = logMetric.id
                        , displayName  = logMetric.displayName
                        , environments = Environment
                                          .values
                                          .filterNot(_ == Environment.Integration)
                                          .map: env =>
                                            ( env
                                            , logMetric.logType
                                            , history.filter(h => h.logType.logMetricId == logMetric.id && h.environment == env)
                                            , collections.find(_.environment == env).map(_.database)
                                            )
                                          .collect:
                                            case (env, _: AppConfig.LogConfigType.AverageMongoDuration, logs, Some(database)) =>
                                              val link = appConfig.kibanaLink(logMetric, service, env, Some(database))
                                              (env.asString, MetricsController.EnvironmentResult(link, logs.size))
                                            case (env, _: AppConfig.LogConfigType.GenericSearch, logs, _) =>
                                              val link = appConfig.kibanaLink(logMetric, service, env)
                                              (env.asString, MetricsController.EnvironmentResult(link, logs.size))
                                          .toMap
                        )
      yield Ok(Json.toJson(results))

object MetricsController:
  import play.api.libs.functional.syntax._

  case class LogMetric(
    id          : AppConfig.LogMetricId
  , displayName : String
  , environments: Map[String, EnvironmentResult] // Note Map[Environment, ...] writes as list
  )

  case class EnvironmentResult(
    kibanaLink: String
  , count     : Int
  )

  object LogMetric:
    val write: Writes[LogMetric] =
      given Writes[EnvironmentResult] =
      ( (__ \ "kibanaLink").write[String]
      ~ (__ \ "count"     ).write[Int]
      )(pt => Tuple.fromProductTyped(pt))

      ( (__ \ "id"          ).write[AppConfig.LogMetricId]
      ~ (__ \ "displayName" ).write[String]
      ~ (__ \ "environments").write[Map[String, EnvironmentResult]]
      )(pt => Tuple.fromProductTyped(pt))
