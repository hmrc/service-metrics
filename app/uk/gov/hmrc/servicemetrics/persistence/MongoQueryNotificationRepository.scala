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
import uk.gov.hmrc.mongo.play.json.Codecs
import uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository
import uk.gov.hmrc.servicemetrics.config.SlackNotificationsConfig
import uk.gov.hmrc.servicemetrics.model.Environment
import MongoQueryLogHistoryRepository.MongoQueryType
import MongoQueryNotificationRepository.MongoQueryNotification

import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class MongoQueryNotificationRepository @Inject()(
  mongoComponent          : MongoComponent,
  slackNotificationsConfig: SlackNotificationsConfig,
)(using
  ExecutionContext
) extends PlayMongoRepository(
  mongoComponent = mongoComponent,
  collectionName = "mongoQueryNotifications",
  domainFormat   = MongoQueryNotification.format,
  indexes        = Seq(
                     IndexModel(Indexes.ascending("service")),
                     IndexModel(Indexes.ascending("environment")),
                     IndexModel(Indexes.ascending("queryType")),
                     IndexModel(Indexes.ascending("collection")),
                     IndexModel(Indexes.ascending("team")),
                     IndexModel(Indexes.ascending("timestamp"), IndexOptions().expireAfter(slackNotificationsConfig.throttlingPeriod.toDays, TimeUnit.DAYS)),
                   ),
  extraCodecs    = Seq(Codecs.playFormatCodec(MongoQueryType.format))
):

  def insertMany(notifications: Seq[MongoQueryNotification]): Future[Unit] =
    collection.insertMany(notifications).toFuture().map(_ => ())

  def hasBeenNotified(team: String): Future[Boolean] =
    collection
      .find(Filters.eq("team", team))
      .limit(1)
      .headOption()
      .map(_.isDefined)

object MongoQueryNotificationRepository:
  case class MongoQueryNotification(
    service    : String,
    database   : String,
    environment: Environment,
    queryType  : MongoQueryType,
    timestamp  : Instant,
    team       : String
  )

  object MongoQueryNotification:
    val format: Format[MongoQueryNotification] =
      ( (__ \ "service"    ).format[String]
      ~ (__ \ "database"   ).format[String]
      ~ (__ \ "environment").format[Environment](Environment.format)
      ~ (__ \ "queryType"  ).format[MongoQueryType](MongoQueryType.format)
      ~ (__ \ "timestamp"  ).format[Instant](MongoJavatimeFormats.instantFormat)
      ~ (__ \ "team"       ).format[String]
      )(MongoQueryNotification.apply, o => Tuple.fromProductTyped(o))
