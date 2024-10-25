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
import org.mongodb.scala.model.{Filters, IndexModel, IndexOptions, Indexes, Sorts}
import play.api.Configuration
import play.api.libs.functional.syntax._
import play.api.libs.json._
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.Codecs
import uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository
import uk.gov.hmrc.servicemetrics.model.Environment

import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.Duration
import uk.gov.hmrc.servicemetrics.config.AppConfig

@Singleton
class NotificationRepository @Inject()(
  config        : Configuration,
  mongoComponent: MongoComponent,
)(using
  ExecutionContext
) extends PlayMongoRepository(
  mongoComponent = mongoComponent,
  collectionName = "notifications",
  domainFormat   = NotificationRepository.Notification.format,
  indexes        = Seq(
                     IndexModel(Indexes.ascending("team"))
                   , IndexModel(Indexes.ascending("logType.logMetricId"))
                   , IndexModel(Indexes.ascending("timestamp"), IndexOptions().expireAfter(config.get[Duration]("alerts.slack.throttling-period").toDays, TimeUnit.DAYS))
                   ),
  extraCodecs    = Seq(Codecs.playFormatCodec(LogHistoryRepository.LogType.format))
):

  def flagAsNotified(notifications: Seq[NotificationRepository.Notification]): Future[Unit] =
    collection
      .insertMany(notifications)
      .toFuture()
      .map(_ => ())

  def hasBeenNotified(team: String, logMetricId: AppConfig.LogMetricId): Future[Boolean] =
    collection
      .find(
        Filters.and(
          Filters.eq("team", team)
        , Filters.eq("logType.logMetricId", logMetricId.asString)
        )
      )
      .limit(1)
      .headOption()
      .map(_.isDefined)

  def lastInsertDate(): Future[Option[Instant]] =
    collection
      .find()
      .sort(Sorts.descending("timestamp"))
      .limit(1)
      .map(_.timestamp)
      .headOption()

object NotificationRepository:
  case class Notification(
    service    : String,
    environment: Environment,
    logType    : LogHistoryRepository.LogType,
    timestamp  : Instant,
    team       : String
  )

  object Notification:
    val format: Format[Notification] =
      ( (__ \ "service"    ).format[String]
      ~ (__ \ "environment").format[Environment]
      ~ (__ \ "logType"    ).format[LogHistoryRepository.LogType](LogHistoryRepository.LogType.format)
      ~ (__ \ "timestamp"  ).format[Instant](MongoJavatimeFormats.instantFormat)
      ~ (__ \ "team"       ).format[String]
      )(Notification.apply, o => Tuple.fromProductTyped(o))
