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

import org.mongodb.scala.model.{Filters, IndexModel, IndexOptions, Indexes}
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

import MongoQueryLogHistoryRepository._

@Singleton
class MongoQueryLogHistoryRepository @Inject()(
  mongoComponent: MongoComponent
)(implicit
  ec: ExecutionContext
) extends PlayMongoRepository(
  mongoComponent = mongoComponent,
  collectionName = MongoQueryLogHistoryRepository.collectionName,
  domainFormat   = MongoQueryLogHistory.format,
  indexes        = Seq(
      IndexModel(Indexes.ascending("service")),
      IndexModel(Indexes.ascending("environment")),
      IndexModel(Indexes.ascending("queryType")),
      IndexModel(Indexes.ascending("timestamp"), IndexOptions().expireAfter(90, TimeUnit.DAYS)),
    ),
  extraCodecs    = Seq(Codecs.playFormatCodec(MongoQueryType.format))
) {

  def getQueryTypesByService(
    service    : String,
    from       : Instant,
    to         : Instant,
  ): Future[Seq[NonPerformantQueries]] = {

    val filters = Seq(
      Filters.equal("service", service),
      Filters.and(
        Filters.gte("timestamp", from),
        Filters.lte("timestamp", to)
      ),
    )

    collection
      .find(Filters.and(filters: _*))
      .toFuture()
      .map(_.groupBy(_.environment))
      .map(_.map{ case (environment, results) =>
        NonPerformantQueries(
          service,
          environment,
          results.map(_.queryType).distinct
        )
      }.toSeq)
  }

  def getAll(
    environment: Environment,
    from       : Instant,
    to         : Instant,
  ): Future[Seq[MongoQueryLogHistory]] = {

    val filters = Seq(
      Filters.equal("environment", environment.asString),
      Filters.and(
        Filters.gte("timestamp", from),
        Filters.lte("timestamp", to)
      )
    )

    collection.find(
        filter = Filters.and(filters: _*)
      )
      .toFuture()
  }

  def insertMany(logs: Seq[MongoQueryLogHistory]): Future[Unit] =
    collection.insertMany(logs).toFuture().map(_ => ())
}

object MongoQueryLogHistoryRepository {
  val collectionName = "mongoQueryLogHistory"

  final case class MongoQueryLogHistory(
    timestamp  : Instant,
    collection : String,
    database   : String,
    mongoDb    : String,
    operation  : Option[String],
    duration   : Int,
    service    : String,
    queryType  : MongoQueryType,
    environment: Environment,
  )

  object MongoQueryLogHistory{
    private implicit val instantFormat: Format[Instant] = MongoJavatimeFormats.instantFormat
    val format: Format[MongoQueryLogHistory] =
      ( (__ \ "timestamp"  ).format[Instant]
      ~ (__ \ "collection" ).format[String]
      ~ (__ \ "database"   ).format[String]
      ~ (__ \ "mongoDb"    ).format[String]
      ~ (__ \ "operation"  ).formatNullable[String]
      ~ (__ \ "duration"   ).format[Int]
      ~ (__ \ "service"    ).format[String]
      ~ (__ \ "queryType"  ).format[MongoQueryType](MongoQueryType.format)
      ~ (__ \ "environment").format[Environment](Environment.format)
      )(MongoQueryLogHistory.apply _, unlift(MongoQueryLogHistory.unapply _))
  }

  sealed trait MongoQueryType { val value: String }
  
  object MongoQueryType {
    case object SlowQuery extends MongoQueryType       { val value: String = "Slow Running Query" }
    case object NonIndexedQuery extends MongoQueryType { val value: String = "Non Indexed Collection Query" }

    case class OtherQuery(val value: String) extends MongoQueryType

    val values: Seq[MongoQueryType] = Seq(SlowQuery, NonIndexedQuery)
    val format: Format[MongoQueryType] = new Format[MongoQueryType] {
      override def writes(o: MongoQueryType): JsValue = JsString(o.value)
      override def reads(json: JsValue): JsResult[MongoQueryType] =
        json.validate[String]
          .flatMap(s => 
            MongoQueryType.values.find(_.value == s)
              .map(e => JsSuccess(e)).getOrElse(JsError("Invalid MongoDb query type"))
          )
    }
  }

  final case class NonPerformantQueries(
    service    : String,
    environment: Environment,
    queryTypes : Seq[MongoQueryType],
  )

  object NonPerformantQueries{
    private implicit val mqtFormat = MongoQueryType.format
    val format: Format[NonPerformantQueries] =
      ( (__ \ "service"    ).format[String]
      ~ (__ \ "environment").format[Environment](Environment.format)
      ~ (__ \ "queryTypes" ).format[Seq[MongoQueryType]]
      )(NonPerformantQueries.apply _, unlift(NonPerformantQueries.unapply _))
  }
}
