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

import cats.implicits.*
import play.api.libs.json.{Json, Writes, __}
import play.api.mvc.{Action, AnyContent, ControllerComponents, RequestHeader}
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController
import uk.gov.hmrc.servicemetrics.config.AppConfig
import uk.gov.hmrc.servicemetrics.config.AppConfig.LogMetricId
import uk.gov.hmrc.servicemetrics.connector.TeamsAndRepositoriesConnector
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
, teamsAndRepositoriesConnector      : TeamsAndRepositoriesConnector
)(using
  ExecutionContext
) extends BackendController(cc):

  def getCollections(service: String, environment: Option[Environment]): Action[AnyContent] =
    Action.async:
      given Writes[MongoCollectionSize] = MongoCollectionSize.apiWrites
      latestMongoCollectionSizeRepository
        .find(Some(Seq(service)), environment)
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
        history     <- logHistoryRepository.find(services = Some(Seq(service)), from = from, to = to)
        collections <- latestMongoCollectionSizeRepository.find(Some(Seq(service)))
        results     =  appConfig.logMetrics
                         .filterNot(_._1 == AppConfig.LogMetricId.OrphanToken)
                         .map: (logMetricId, logMetric) =>
                           MetricsController.LogMetric(
                             id           = logMetricId
                           , displayName  = logMetric.displayName
                           , environments = Environment
                                             .applicableValues
                                             .map: env =>
                                               ( env
                                               , logMetric.logType
                                               , history.filter(h => h.logType.logMetricId == logMetricId && h.environment == env)
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

  def getAllLogMetrics(
    environment   : Option[Environment]
  , teamName      : Option[String]
  , digitalService: Option[String]
  , metricType    : Option[LogMetricId]
  , from          : Instant
  , to            : Instant
  ): Action[AnyContent] =
    Action.async: request =>
      given RequestHeader = request
      given Writes[MetricsController.ServiceMetric] = MetricsController.ServiceMetric.write
      for
        services      <- teamsAndRepositoriesConnector.findServices(teamName, digitalService)
        oServiceNames =  (teamName, digitalService) match
                           case (None, None) => None
                           case _            => Some(services.map(_.name))
        history       <- logHistoryRepository.find(
                           services    = oServiceNames
                         , environment = environment
                         , metricType  = metricType
                         , from        = from
                         , to          = to
                         )
        collections   <- latestMongoCollectionSizeRepository.find(oServiceNames, environment)
        results       =  history
                           .groupBy(log => (log.service, log.logType.logMetricId, log.environment))
                           .collect:
                             case ((serviceName, logMetricId, environment), logs) if logMetricId != AppConfig.LogMetricId.OrphanToken =>
                               val logMetric = appConfig.logMetrics(logMetricId)
                               val oDatabase = collections.find(mcs => mcs.service == serviceName && mcs.environment == environment).map(_.database)
                               val oService  = services.find(_.name == serviceName)
                               MetricsController.ServiceMetric(
                                 service         = serviceName
                               , id              = logMetricId
                               , teams           = oService.fold(Nil)(_.teamNames)
                               , environment     = environment
                               , kibanaLink      = appConfig.kibanaLink(logMetric, serviceName, environment, oDatabase)
                               , logCount        = logMetric.logType match
                                                     case _: AppConfig.LogConfigType.AverageMongoDuration =>
                                                       logs.flatMap(_.logType.asInstanceOf[LogHistoryRepository.LogType.AverageMongoDuration].details.map(_.occurrences)).sum
                                                     case _: AppConfig.LogConfigType.GenericSearch        =>
                                                       logs.map(_.logType.asInstanceOf[LogHistoryRepository.LogType.GenericSearch].details).sum
                               )
                           .toSeq
      yield Ok(Json.toJson(results.sortBy(_.service)))

object MetricsController:
  import play.api.libs.functional.syntax._

  case class EnvironmentResult(
    kibanaLink: String
  , count     : Int
  )

  case class LogMetric(
    id          : AppConfig.LogMetricId
  , displayName : String
  , environments: Map[String, EnvironmentResult] // Note Map[Environment, ...] writes as list
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

  case class ServiceMetric(
    service        : String
  , id             : AppConfig.LogMetricId
  , environment    : Environment
  , teams          : Seq[String]
  , kibanaLink     : String
  , logCount       : Int
  )

  object ServiceMetric:
    val write: Writes[ServiceMetric] =
      ( (__ \ "service"        ).write[String]
      ~ (__ \ "id"             ).write[AppConfig.LogMetricId]
      ~ (__ \ "environment"    ).write[Environment]
      ~ (__ \ "teams"          ).write[Seq[String]]
      ~ (__ \ "kibanaLink"     ).write[String]
      ~ (__ \ "logCount"       ).write[Int]
      )(pt => Tuple.fromProductTyped(pt))
