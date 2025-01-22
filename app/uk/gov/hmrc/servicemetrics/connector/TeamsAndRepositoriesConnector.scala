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

import play.api.libs.functional.syntax.toFunctionalBuilderOps
import play.api.libs.json.*
import uk.gov.hmrc.http.HttpReads.Implicits.*
import uk.gov.hmrc.http.{HeaderCarrier, StringContextOps}
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig
import uk.gov.hmrc.servicemetrics.connector.TeamsAndRepositoriesConnector.Service

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class TeamsAndRepositoriesConnector @Inject() (
  httpClientV2  : HttpClientV2,
  servicesConfig: ServicesConfig
)(using ExecutionContext):

  private val teamsAndRepositoriesBaseUrl: String =
    servicesConfig.baseUrl("teams-and-repositories")

  private given Reads[Service] = Service.reads

  def allServices()(using HeaderCarrier): Future[Seq[Service]] =
    httpClientV2
      .get(url"$teamsAndRepositoriesBaseUrl/api/v2/repositories?repoType=service")
      .execute[Seq[Service]]

  def allDeletedServices()(using HeaderCarrier): Future[Seq[Service]] =
    httpClientV2
      .get(url"$teamsAndRepositoriesBaseUrl/api/deleted-repositories?repoType=service")
      .execute[Seq[Service]]

  def findServices(
    owningTeam    : Option[String]
  , digitalService: Option[String]
  )(using hc: HeaderCarrier): Future[Seq[Service]] =
    httpClientV2
      .get(url"$teamsAndRepositoriesBaseUrl/api/v2/repositories?repoType=service&owningTeam=$owningTeam&digitalServiceName=$digitalService")
      .execute[Seq[Service]]

object TeamsAndRepositoriesConnector:
  case class Service(
    name           : String
  , teamNames      : Seq[String]
  , digitalServices: Seq[String]
  )

  object Service:
    val reads: Reads[Service] =
      ( (__ \ "name"           ).read[String]
      ~ (__ \ "teamNames"      ).readWithDefault[Seq[String]](Seq.empty)
      ~ (__ \ "digitalServices").readWithDefault[Seq[String]](Seq.empty)
      )(Service.apply)
