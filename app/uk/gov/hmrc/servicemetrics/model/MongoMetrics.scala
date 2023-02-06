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

package uk.gov.hmrc.servicemetrics.model

import play.api.libs.functional.syntax.{toFunctionalBuilderOps, unlift}
import play.api.libs.json.{OFormat, Reads, __}

import java.time.LocalDate

case class MongoCollectionSize(service: String, collection: String, sizeBytes: BigDecimal, date: LocalDate, environment: String, latest: Boolean)

object MongoCollectionSize {
  val format: OFormat[MongoCollectionSize] =
    ( (__ \ "service"      ).format[String]
      ~ (__ \ "collection").format[String]
      ~ (__ \ "sizeBytes").format[BigDecimal]
      ~ (__ \ "date").format[LocalDate]
      ~ (__ \ "environment").format[String]
      ~ (__ \ "latest").format[Boolean]
      )(apply, unlift(unapply))
}

case class Datapoint(size: BigDecimal, timestamp: Long)

object Datapoint {

  private def toMbs(num: BigDecimal) = num / (1024 * 1024)

//    val reads: Reads[Datapoint] =
//      ( (__ \ "size"        ).read[BigDecimal]
//        ~ (__ \ "timestamp" ).read[Long]
//        )(apply _)

//    val writes: Writes[Datapoint] =
//      ( (__ \ "fileSizeMbs").write[BigDecimal].contramap(toMbs)
//        ~ (__ \ "timestamp").write[Instant].contramap(Instant.ofEpochSecond)
//        )(unlift(unapply))
}

case class MongoMetrics(target: String, datapoints: Seq[Datapoint])

object MongoMetrics {
  implicit val reads: Reads[MongoMetrics] = {
    //implicit val dpF = Datapoint.reads
    ( (__ \ "target"      ).read[String]
      ~ (__ \ "datapoints").read[Seq[(BigDecimal, Long)]].map(_.map(dp => Datapoint(dp._1, dp._2)))
      )(apply _)
  }

//  implicit val writes: Writes[MongoMetrics] = {
//    implicit val dpW = Datapoint.writes
//    ( (__ \ "collection"  ).write[String].contramap((s: String) => s.replaceFirst("mongo-", ""))
//      ~ (__ \ "datapoints").write[Seq[Datapoint]]
//      )(unlift(unapply))
//  }

}








