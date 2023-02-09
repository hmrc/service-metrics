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
import uk.gov.hmrc.servicemetrics.config.GitHubConfig
import uk.gov.hmrc.servicemetrics.connector.GitHubConnector.DbOverride
import uk.gov.hmrc.servicemetrics.model.Environment

import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class GitHubConnector @Inject()(httpClientV2 : HttpClientV2,
                                gitHubConfig: GitHubConfig)(implicit ec: ExecutionContext) {


  def getMongoOverrides(environment: Environment)(implicit hc: HeaderCarrier): Future[Seq[DbOverride]] = {
    httpClientV2
      .get(url"${gitHubConfig.githubRawUrl}/hmrc/vault-policy-definitions-${environment.asString}/main/db-overrides.json")
      .setHeader("Authorization" -> s"token ${gitHubConfig.githubToken}")
      .withProxy
      .execute[Map[String, Seq[JsObject]]]
      .map(
        _
          .flatMap { case (k, vs) =>
            vs.map(v => DbOverride(k, (v \ "dbs").as[Seq[String]]))
          }.toSeq
      )
  }

}

object GitHubConnector {
  case class DbOverride(service: String, dbs: Seq[String])
}
