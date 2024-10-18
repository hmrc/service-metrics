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

package uk.gov.hmrc.servicemetrics.persistence

import org.mongodb.scala.ObservableFuture
import org.mongodb.scala.model.{Filters, IndexModel, IndexOptions, Indexes}
import play.api.libs.functional.syntax._
import play.api.libs.json._
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.{Codecs, PlayMongoRepository}
import uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats
import uk.gov.hmrc.servicemetrics.model.Environment

import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import uk.gov.hmrc.servicemetrics.config.AppConfig

@Singleton
class LogHistoryRepository @Inject()(
  mongoComponent: MongoComponent
)(using
  ExecutionContext
) extends PlayMongoRepository(
  mongoComponent = mongoComponent,
  collectionName = "logHistory",
  domainFormat   = LogHistoryRepository.LogHistory.format,
  indexes        = Seq(
                     IndexModel(Indexes.ascending("service")),
                     IndexModel(Indexes.ascending("environment")),
                     IndexModel(Indexes.ascending("queryType")),
                     IndexModel(Indexes.ascending("since")),
                     IndexModel(Indexes.ascending("timestamp"), IndexOptions().expireAfter(90, TimeUnit.DAYS)),
                   ),
  extraCodecs    = Seq(Codecs.playFormatCodec(LogHistoryRepository.LogType.format))
):
  def find(
     service: Option[String] = None
  ,  from   : Instant
  ,  to     : Instant
  ): Future[Seq[LogHistoryRepository.LogHistory]] =
    collection
      .find(
        Filters.and(
          service.fold(Filters.empty())(s => Filters.equal("service", s)),
          Filters.or(
            Filters.and(Filters.gte("timestamp", from), Filters.lte("timestamp", to  )),
            Filters.and(Filters.gte("since"    , from), Filters.lte("since"    , to  )),
            Filters.and(Filters.gte("since"    , from), Filters.lte("timestamp", from)),
            Filters.and(Filters.gte("since"    , to  ), Filters.lte("timestamp", to  ))
          )
        )
      )
      .toFuture()

  def insertMany(logs: Seq[LogHistoryRepository.LogHistory]): Future[Unit] =
    collection.insertMany(logs).toFuture().map(_ => ())

object LogHistoryRepository:
  case class LogHistory(
    timestamp  : Instant,
    since      : Instant,
    service    : String,
    logType    : LogType,
    environment: Environment,
    teams      : Seq[String],
  )

  object LogHistory :
    val format: Format[LogHistory] =
      given Format[Instant] = MongoJavatimeFormats.instantFormat
      ( (__ \ "timestamp"  ).format[Instant]
      ~ (__ \ "since"      ).format[Instant]
      ~ (__ \ "service"    ).format[String]
      ~ (__ \ "logType"    ).format[LogType](LogType.format)
      ~ (__ \ "environment").format[Environment]
      ~ (__ \ "teams"      ).format[Seq[String]]
      )(LogHistory.apply, pt => Tuple.fromProductTyped(pt))

  enum LogType(val logMetricId: AppConfig.LogMetricId):
    case GenericSearch       (override val logMetricId: AppConfig.LogMetricId, details: Int                                   ) extends LogType(logMetricId)
    case AverageMongoDuration(override val logMetricId: AppConfig.LogMetricId, details: Seq[AverageMongoDuration.MongoDetails]) extends LogType(logMetricId)

  object LogType:
    object AverageMongoDuration:
      case class MongoDetails(
        database   : String
      , collection : String
      , duration   : Double
      , occurrences: Int
      )

    val format: Format[LogType] = new Format[LogType]:
      given Format[GenericSearch] =
        ( (__ \ "logMetricId").format[AppConfig.LogMetricId]
        ~ (__ \ "details"    ).format[Int]
        )(GenericSearch.apply, pt => Tuple.fromProductTyped(pt))

      given Format[AverageMongoDuration] =
        given Format[AverageMongoDuration.MongoDetails] =
          ( (__ \ "database"   ).format[String]
          ~ (__ \ "collection" ).format[String]
          ~ (__ \ "duration"   ).format[Double]
          ~ (__ \ "occurrences").format[Int]
          )(AverageMongoDuration.MongoDetails.apply, pt => Tuple.fromProductTyped(pt))

        ( (__ \ "logMetricId").format[AppConfig.LogMetricId]
        ~ (__ \ "details"    ).format[Seq[AverageMongoDuration.MongoDetails]]
        )(AverageMongoDuration.apply, pt => Tuple.fromProductTyped(pt))

      override def writes(o: LogType): JsValue = o match
        case x: GenericSearch        => Json.toJson(x)
        case x: AverageMongoDuration => Json.toJson(x)

      override def reads(json: JsValue): JsResult[LogType] =
        json
          .validate[GenericSearch]
          .orElse(json.validate[AverageMongoDuration])
