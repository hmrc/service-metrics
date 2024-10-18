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

package uk.gov.hmrc.servicemetrics.connector

import play.api.Configuration
import play.api.libs.json.JsValue
import uk.gov.hmrc.http.HttpReads.Implicits._
import uk.gov.hmrc.http.{HeaderCarrier, StringContextOps}
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.servicemetrics.model.Environment

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ClickHouseConnector @Inject()(
  configuration: Configuration
, httpClientV2 : HttpClientV2
)(using
  ExecutionContext
):

  private val environmentUrls: Map[Environment, String] =
    Environment
      .values
      .filterNot(_ == Environment.Integration)
      .map:
        env => env -> configuration.get[String](s"clickhouse.${env.asString}.url")
      .toMap

  def getDatabaseNames(environment: Environment)(using HeaderCarrier): Future[Seq[String]] =
    httpClientV2
      .get(url"${environmentUrls(environment)}/latest/mongodbs")
      .execute[JsValue]
      .map(json => (json \ "name").as[Seq[String]])
