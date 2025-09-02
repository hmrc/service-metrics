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
import uk.gov.hmrc.mongo.transaction.{TransactionConfiguration, Transactions}
import uk.gov.hmrc.servicemetrics.model.Environment

import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
@Singleton
class ServiceProvisionRepository @Inject()(
  override val mongoComponent: MongoComponent,
)(using
  ExecutionContext
) extends PlayMongoRepository(
  mongoComponent = mongoComponent
, collectionName = "serviceProvision"
, domainFormat   = ServiceProvisionRepository.ServiceProvision.mongoFormat
, indexes        = IndexModel(Indexes.ascending("service"))                                              ::
                   IndexModel(Indexes.ascending("since"))                                                ::
                   IndexModel(Indexes.ascending("from"), IndexOptions().expireAfter(730, TimeUnit.DAYS)) ::
                   IndexModel(Indexes.ascending("environment"))                                          ::
                   Nil
, extraCodecs    = Seq(Codecs.playFormatCodec(ServiceProvisionRepository.ServiceProvision.mongoFormat))
) with Transactions:

  private given TransactionConfiguration = TransactionConfiguration.strict

  import scala.math.Ordered.orderingToOrdered
  def insertMany(environment: Environment, from: Instant, to: Instant, metrics: Seq[ServiceProvisionRepository.ServiceProvision]): Future[Unit] =
    if  metrics.exists(_.environment != environment) then
      Future.failed(sys.error(s"${environment.asString} does not match service provision metrics ${metrics.collect { case x if x.environment != environment => x.environment}.distinct.mkString(", ")}"))
    else if metrics.exists(x => x.from < from || x.to > to) then
      Future.failed(sys.error(s"$from - $to does not cover service provision date ranges: ${metrics.collect { case x if x.from <= from || x.to >= to => s"${x.from} - ${x.to}" }.distinct.mkString(", ")}"))
    else
      withSessionAndTransaction: session =>
        for
          _ <- collection.deleteMany(session, Filters.and(
                 Filters.eq("environment", environment.asString)
               , Filters.gte("from", from)
               , Filters.lte("to", to)
               )).toFuture()
          _ <- collection.insertMany(session, metrics).toFuture()
        yield ()

  def find(
     services   : Option[Seq[String]] = None
  ,  environment: Option[Environment] = None
  ,  from       : Instant
  ,  to         : Instant
  ): Future[Seq[ServiceProvisionRepository.ServiceProvision]] =
    collection
      .find(
        Filters.and(
          services.fold(Filters.empty)(s => Filters.in("service", s:_*))
        , environment.fold(Filters.empty())(e => Filters.equal("environment", e.asString))
        , Filters.gte("from", from)
        , Filters.lte("to"  , to  )
        )
      )
      .toFuture()

object ServiceProvisionRepository:
  case class ServiceProvision(
    from       : Instant
  , to         : Instant
  , service    : String
  , environment: Environment
  , metrics    : Map[String, BigDecimal]
  )

  object ServiceProvision :
    val mongoFormat: Format[ServiceProvision] =
      given Format[Instant] = MongoJavatimeFormats.instantFormat
      ( (__ \ "from"       ).format[Instant]
      ~ (__ \ "to"         ).format[Instant]
      ~ (__ \ "service"    ).format[String]
      ~ (__ \ "environment").format[Environment]
      ~ (__ \ "metrics"    ).format[Map[String, BigDecimal]]
      )(ServiceProvision.apply, pt => Tuple.fromProductTyped(pt))

    val apiWrites: Writes[ServiceProvision] =
      ( (__ \ "from"       ).write[Instant]
      ~ (__ \ "to"         ).write[Instant]
      ~ (__ \ "service"    ).write[String]
      ~ (__ \ "environment").write[Environment]
      ~ (__ \ "metrics"    ).write[Map[String, BigDecimal]]
      )(pt => Tuple.fromProductTyped(pt))
