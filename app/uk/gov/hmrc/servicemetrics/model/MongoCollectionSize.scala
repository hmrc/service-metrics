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
import play.api.libs.json.{OFormat, __}

import java.time.LocalDate

case class MongoCollectionSize(
  database    : String
, collection  : String
, sizeBytes   : BigDecimal
, date        : LocalDate
, environment : Environment
, service     : Option[String]
)

object MongoCollectionSize {
  implicit val envf = Environment.format
  val format: OFormat[MongoCollectionSize] =
    ( (__ \ "database"   ).format[String]
    ~ (__ \ "collection" ).format[String]
    ~ (__ \ "sizeBytes"  ).format[BigDecimal]
    ~ (__ \ "date"       ).format[LocalDate]
    ~ (__ \ "environment").format[Environment]
    ~ (__ \ "service"    ).formatNullable[String]
    )(apply, unlift(unapply))
}
