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

case class Version(
  major   : Int,
  minor   : Int,
  patch   : Int,
  original: String
) extends Ordered[Version]:

  override def compare(other: Version): Int =
    import Ordered.*
    (major, minor, patch, original).compare((other.major, other.minor, other.patch, other.original))

  override def toString: String =
    original

object Version:
  def apply(version: String): Version =
    parse(version).getOrElse(sys.error(s"Could not parse version $version"))

  def apply(major: Int, minor: Int, patch: Int): Version =
    Version(major, minor, patch, s"$major.$minor.$patch")

  def parse(version: String): Option[Version] =
    val regex3 = """(\d+)\.(\d+)\.(\d+)(.*)""".r
    val regex2 = """(\d+)\.(\d+)(.*)""".r
    val regex1 = """(\d+)(.*)""".r
    version match
      case regex3(maj, min, patch, _) => Some(Version(Integer.parseInt(maj), Integer.parseInt(min), Integer.parseInt(patch), version))
      case regex2(maj, min,  _)       => Some(Version(Integer.parseInt(maj), Integer.parseInt(min), 0                      , version))
      case regex1(patch,  _)          => Some(Version(0                    , 0                    , Integer.parseInt(patch), version))
      case _                          => Some(Version(0                    , 0                    , 0                      , version))

  import play.api.libs.json.{Reads, JsString, JsSuccess, JsError}
  given reads: Reads[Version] =
    _ match
      case JsString(s) => parse(s).map(v => JsSuccess(v)).getOrElse(JsError("Could not parse range"))
      case _           => JsError("Not a string")
