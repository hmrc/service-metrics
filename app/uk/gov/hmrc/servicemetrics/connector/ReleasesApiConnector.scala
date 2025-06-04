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

import javax.inject.{Inject, Singleton}
import play.api.Logging
import play.api.libs.functional.syntax._
import play.api.libs.json._
import uk.gov.hmrc.http.{HeaderCarrier, HttpReads, StringContextOps}
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.servicemetrics.model.{Environment, Version}

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ReleasesApiConnector @Inject()(
  servicesConfig: uk.gov.hmrc.play.bootstrap.config.ServicesConfig
, httpClientV2  : HttpClientV2
)(using
  ec: ExecutionContext
):
  import ReleasesApiConnector._
  import HttpReads.Implicits._

  private val baseUrl: String =
    servicesConfig.baseUrl("releases-api")

  def whatsRunningWhere()(using hc: HeaderCarrier): Future[Seq[WhatsRunningWhere]] =
    given Reads[WhatsRunningWhere] = WhatsRunningWhere.reads
    httpClientV2
      .get(url"$baseUrl/releases-api/whats-running-where")
      .execute[Seq[WhatsRunningWhere]]

object ReleasesApiConnector extends Logging:
  case class Deployment(
    environment: Environment
  , version    : Version
  )

  object Deployment:
    val reads: Reads[Deployment] =
      import Environment.given
      import Version.given
      ( (__ \ "environment"  ).read[Environment]
      ~ (__ \ "versionNumber").read[Version]
      )(Deployment.apply)

  case class WhatsRunningWhere(
    serviceName: String
  , deployments: Seq[Deployment]
  )

  object WhatsRunningWhere:
    val reads: Reads[WhatsRunningWhere] =
      given Reads[Deployment] = Deployment.reads
      ( (__ \ "applicationName").read[String]
      ~ (__ \ "versions"       ).read[Seq[Deployment]]
      )(WhatsRunningWhere.apply)
