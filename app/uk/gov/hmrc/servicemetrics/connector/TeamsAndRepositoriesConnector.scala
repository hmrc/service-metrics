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

import play.api.libs.json.{Reads, __}
import uk.gov.hmrc.http.{HeaderCarrier, StringContextOps}
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig
import uk.gov.hmrc.servicemetrics.connector.TeamsAndRepositoriesConnector.ServiceName
import uk.gov.hmrc.http.HttpReads.Implicits._

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class TeamsAndRepositoriesConnector @Inject() (
                                                httpClientV2  : HttpClientV2,
                                                servicesConfig: ServicesConfig
                                              )(implicit val ec: ExecutionContext)  {

  private val teamsAndRepositoriesBaseUrl: String =
    servicesConfig.baseUrl("teams-and-repositories")

  def allServices()(implicit hc: HeaderCarrier): Future[Seq[ServiceName]] = {
    implicit val reads: Reads[ServiceName] = ServiceName.reads
    httpClientV2
      .get(url"$teamsAndRepositoriesBaseUrl/api/v2/repositories")
      .execute[Seq[ServiceName]]
  }

}

object TeamsAndRepositoriesConnector {
  case class ServiceName(value: String) extends AnyVal

  object ServiceName {
    val reads: Reads[ServiceName] =
      Reads.at[String](__ \ "name").map(ServiceName(_))
  }
}
