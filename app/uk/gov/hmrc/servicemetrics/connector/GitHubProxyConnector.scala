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

import play.api.libs.json.JsObject
import uk.gov.hmrc.http.HttpReads.Implicits._
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.{HeaderCarrier, StringContextOps}
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig
import uk.gov.hmrc.servicemetrics.connector.GitHubProxyConnector.DbOverride
import uk.gov.hmrc.servicemetrics.model.Environment

import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class GitHubProxyConnector @Inject()(
  httpClientV2  : HttpClientV2
, servicesConfig: ServicesConfig
)(using ExecutionContext):

  private val gitHubProxyBaseURL: String =
    servicesConfig.baseUrl("platops-github-proxy")

  def getMongoOverrides(environment: Environment)(using HeaderCarrier): Future[Seq[DbOverride]] =
    httpClientV2
      .get(url"$gitHubProxyBaseURL/platops-github-proxy/github-raw/vault-policy-definitions-${environment.asString}/main/db-overrides.json")
      .execute[Map[String, Seq[JsObject]]]
      .map:
        _
          .flatMap: (k, vs) =>
            vs.map(v => DbOverride(k, (v \ "dbs").as[Seq[String]]))
          .toSeq

object GitHubProxyConnector:

  case class DbOverride(
    service: String,
    dbs    : Seq[String]
  )
