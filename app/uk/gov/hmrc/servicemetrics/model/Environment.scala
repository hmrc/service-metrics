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

import play.api.libs.json.{Reads, Writes}
import play.api.mvc.{PathBindable, QueryStringBindable}
import uk.gov.hmrc.servicemetrics.util.{FromString, FromStringEnum, Parser}

import FromStringEnum._

given Parser[Environment] = Parser.parser(Environment.values)

enum Environment(
  override val asString: String
, val displayString    : String
) extends FromString
  derives Ordering, Reads, Writes, PathBindable, QueryStringBindable:
  case Integration  extends Environment(asString = "integration" , displayString = "Integration"  )
  case Development  extends Environment(asString = "development" , displayString = "Development"  )
  case QA           extends Environment(asString = "qa"          , displayString = "QA"           )
  case Staging      extends Environment(asString = "staging"     , displayString = "Staging"      )
  case ExternalTest extends Environment(asString = "externaltest", displayString = "External Test")
  case Production   extends Environment(asString = "production"  , displayString = "Production"   )

object Environment:
  val applicableValues: Seq[Environment] =
    Environment.values.toSeq.filterNot(_ == Environment.Integration)
