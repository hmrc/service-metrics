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
import org.mongodb.scala.model.{IndexModel, IndexOptions, Indexes}
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
@Singleton
class ServiceProvisionRepository @Inject()(
  mongoComponent: MongoComponent,
)(using
  ExecutionContext
) extends PlayMongoRepository(
  mongoComponent = mongoComponent
, collectionName = "serviceProvision"
, domainFormat   = ServiceProvisionRepository.Metric.format
, indexes        = IndexModel(Indexes.ascending("service"))                                                   ::
                   IndexModel(Indexes.ascending("since"))                                                     ::
                   IndexModel(Indexes.ascending("timestamp"), IndexOptions().expireAfter(730, TimeUnit.DAYS)) ::
                   IndexModel(Indexes.ascending("environment"))                                               ::
                   Nil
, extraCodecs    = Seq(Codecs.playFormatCodec(ServiceProvisionRepository.Metric.format))
):

  def insertMany(metrics: Seq[ServiceProvisionRepository.Metric]): Future[Unit] =
    collection.insertMany(metrics).toFuture().map(_ => ())

object ServiceProvisionRepository:
  case class Metric(
    from       : Instant
  , to         : Instant
  , service    : String
  , environment: Environment
  , metrics    : Map[String, BigDecimal]
  )

  object Metric :
    val format: Format[Metric] =
      given Format[Instant] = MongoJavatimeFormats.instantFormat
      ( (__ \ "from"       ).format[Instant]
      ~ (__ \ "to"         ).format[Instant]
      ~ (__ \ "service"    ).format[String]
      ~ (__ \ "environment").format[Environment]
      ~ (__ \ "metrics"    ).format[Map[String, BigDecimal]]
      )(Metric.apply, pt => Tuple.fromProductTyped(pt))
