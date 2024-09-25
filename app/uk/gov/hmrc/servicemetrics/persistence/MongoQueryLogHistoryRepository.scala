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

import MongoQueryLogHistoryRepository._

@Singleton
class MongoQueryLogHistoryRepository @Inject()(
  mongoComponent: MongoComponent
)(using
  ExecutionContext
) extends PlayMongoRepository(
  mongoComponent = mongoComponent,
  collectionName = "mongoQueryLogHistory",
  domainFormat   = MongoQueryLogHistory.format,
  indexes        = Seq(
                     IndexModel(Indexes.ascending("service")),
                     IndexModel(Indexes.ascending("environment")),
                     IndexModel(Indexes.ascending("queryType")),
                     IndexModel(Indexes.ascending("since")),
                     IndexModel(Indexes.ascending("timestamp"), IndexOptions().expireAfter(90, TimeUnit.DAYS)),
                   ),
  extraCodecs    = Seq(Codecs.playFormatCodec(MongoQueryType.format))
):

  def getQueryTypesByService(
    service    : String,
    from       : Instant,
    to         : Instant,
  ): Future[Seq[NonPerformantQueries]] =
    collection
      .find(
        Filters.and(
          Filters.equal("service", service),
          Filters.or(
            Filters.and(Filters.gte("timestamp", from), Filters.lte("timestamp", to  )),
            Filters.and(Filters.gte("since"    , from), Filters.lte("since"    , to  )),
            Filters.and(Filters.gte("since"    , from), Filters.lte("timestamp", from)),
            Filters.and(Filters.gte("since"    , to  ), Filters.lte("timestamp", to  ))
          )
        )
      )
      .toFuture()
      .map(_.groupBy(_.environment))
      .map(_.map { case (environment, results) =>
        NonPerformantQueries(
          service,
          environment,
          results.map(_.queryType).distinct
        )
      }.toSeq)

  def getAll(
    environment: Environment,
    from       : Instant,
    to         : Instant,
  ): Future[Seq[MongoQueryLogHistory]] =
    collection
      .find(
        Filters.and(
          Filters.equal("environment", environment.asString),
          Filters.or(
            Filters.and(Filters.gte("timestamp", from), Filters.lte("timestamp", to  )),
            Filters.and(Filters.gte("since"    , from), Filters.lte("since"    , to  )),
            Filters.and(Filters.gte("since"    , from), Filters.lte("timestamp", from)),
            Filters.and(Filters.gte("since"    , to  ), Filters.lte("timestamp", to  ))
          )
        )
      )
      .toFuture()

  def insertMany(logs: Seq[MongoQueryLogHistory]): Future[Unit] =
    collection.insertMany(logs).toFuture().map(_ => ())

  def lastInsertDate(): Future[Option[Instant]] =
    collection
      .find()
      .sort(Sorts.descending("timestamp"))
      .limit(1)
      .map(_.timestamp)
      .headOption()

object MongoQueryLogHistoryRepository:
  case class NonPerformantQueryDetails(
    collection : String,
    duration   : Int,
    occurrences: Int
  )

  object NonPerformantQueryDetails:
    val format: Format[NonPerformantQueryDetails] =
      ( (__ \ "collection").format[String]
      ~ (__ \ "duration"  ).format[Int]
      ~ (__ \ "occurences").format[Int]
      )(NonPerformantQueryDetails.apply, o => Tuple.fromProductTyped(o))

  case class MongoQueryLogHistory(
    timestamp  : Instant,
    since      : Instant,
    database   : String,
    service    : String,
    queryType  : MongoQueryType,
    details    : Seq[NonPerformantQueryDetails],
    environment: Environment,
    teams      : Seq[String],
  )

  object MongoQueryLogHistory :
    val format: Format[MongoQueryLogHistory] =
      given Format[Instant]                   = MongoJavatimeFormats.instantFormat
      given Format[NonPerformantQueryDetails] = NonPerformantQueryDetails.format
      ( (__ \ "timestamp"  ).format[Instant]
      ~ (__ \ "since"      ).format[Instant]
      ~ (__ \ "database"   ).format[String]
      ~ (__ \ "service"    ).format[String]
      ~ (__ \ "queryType"  ).format[MongoQueryType](MongoQueryType.format)
      ~ (__ \ "details"    ).format[Seq[NonPerformantQueryDetails]]
      ~ (__ \ "environment").format[Environment](Environment.format)
      ~ (__ \ "teams"      ).format[Seq[String]]
      )(MongoQueryLogHistory.apply, o => Tuple.fromProductTyped(o))

  sealed trait MongoQueryType { val value: String }

  object MongoQueryType:
    case object SlowQuery                     extends MongoQueryType { val value: String = "Slow Running Query" }
    case object NonIndexedQuery               extends MongoQueryType { val value: String = "Non-indexed Collection Query" }
    case class  OtherQuery(val value: String) extends MongoQueryType

    val values: Seq[MongoQueryType] = Seq(SlowQuery, NonIndexedQuery)

    val format: Format[MongoQueryType] =
      new Format[MongoQueryType]:
        override def writes(o: MongoQueryType): JsValue = JsString(o.value)
        override def reads(json: JsValue): JsResult[MongoQueryType] =
          json.validate[String]
            .flatMap(s =>
              MongoQueryType.values.find(_.value == s)
                .map(e => JsSuccess(e)).getOrElse(JsError("Invalid MongoDb query type"))
            )

  case class NonPerformantQueries(
    service    : String,
    environment: Environment,
    queryTypes : Seq[MongoQueryType],
  )

  object NonPerformantQueries:
    val format: Format[NonPerformantQueries] =
      given Format[MongoQueryType] = MongoQueryType.format
      ( (__ \ "service"    ).format[String]
      ~ (__ \ "environment").format[Environment](Environment.format)
      ~ (__ \ "queryTypes" ).format[Seq[MongoQueryType]]
      )(NonPerformantQueries.apply, o => Tuple.fromProductTyped(o))
