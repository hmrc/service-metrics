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

import com.github.tomakehurst.wiremock.client.WireMock._
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.Configuration
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.test.{HttpClientV2Support, WireMockSupport}
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig
import uk.gov.hmrc.servicemetrics.connector.GitHubProxyConnector.DbOverride
import uk.gov.hmrc.servicemetrics.model.Environment

import scala.concurrent.ExecutionContext.Implicits.global

class GitHubProxyConnectorSpec
  extends AnyWordSpec
  with Matchers
  with ScalaFutures
  with IntegrationPatience
  with HttpClientV2Support
  with WireMockSupport {

  private lazy val gitHubProxyConnector =
    new GitHubProxyConnector(
      httpClientV2   = httpClientV2,
      new ServicesConfig(Configuration(
        "microservice.services.platops-github-proxy.port" -> wireMockPort,
        "microservice.services.platops-github-proxy.host" -> wireMockHost
      ))
    )

  implicit val headerCarrier: HeaderCarrier = HeaderCarrier()


  "getMongoOverrides" should {

    val overridesRaw =
      """
        |{
        |  "service-one": [
        |    {
        |      "replicaset": "public",
        |      "dbs": [
        |        "serviceone"
        |      ]
        |    }
        |  ],
        |  "service-two": [
        |    {
        |      "replicaset": "public",
        |      "dbs": [
        |        "randomdb"
        |      ]
        |    }
        |  ]
        |}
        |""".stripMargin

    "return DBOverrides" in {
      stubFor(
        get(urlEqualTo("/platops-github-proxy/github-raw/vault-policy-definitions-qa/main/db-overrides.json"))
          .willReturn(
            aResponse()
              .withStatus(200)
              .withBody(overridesRaw)
          )
      )

      val expected = Seq(
        DbOverride(service = "service-one", dbs = Seq("serviceone")),
        DbOverride(service = "service-two", dbs = Seq("randomdb"))
      )

      val response = gitHubProxyConnector
        .getMongoOverrides(Environment.QA)
        .futureValue

      response should contain theSameElementsAs expected

      verify(
        getRequestedFor(urlEqualTo("/platops-github-proxy/github-raw/vault-policy-definitions-qa/main/db-overrides.json"))
      )
    }
  }
}
