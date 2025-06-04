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
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.when
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.mockito.MockitoSugar
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.test.{HttpClientV2Support, WireMockSupport}
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig
import uk.gov.hmrc.servicemetrics.connector.CarbonApiConnector.Metric
import uk.gov.hmrc.servicemetrics.model.Environment

import java.time.Instant
import scala.concurrent.ExecutionContext.Implicits.global

class CarbonApiConnectorSpec
  extends AnyWordSpec
    with Matchers
    with ScalaFutures
    with IntegrationPatience
    with HttpClientV2Support
    with WireMockSupport
    with MockitoSugar:

  private given HeaderCarrier = HeaderCarrier()

  private val mockConfig: ServicesConfig = mock[ServicesConfig]

  when(mockConfig.baseUrl(any[String]))
    .thenReturn(wireMockUrl)

  val connector = CarbonApiConnector(httpClientV2, mockConfig)

  "getMongoMetrics" should:
    "return mongo metrics" in:
      stubFor:
        get(urlPathEqualTo("/render"))
          .willReturn:
            aResponse()
              .withStatus(200)
              .withBody("""
                [
                  {
                    "target": "mongo-service-one-collection-one",
                    "datapoints": [
                      [
                        1676590,
                        1675606800
                      ]
                    ],
                    "tags": {
                      "aggregatedBy": "max",
                      "name": "mongo-service-one-collection-one"
                    }
                  },
                  {
                    "target": "mongo-service-one-collection-two",
                    "datapoints": [
                      [
                        79688835,
                        1675606800
                      ]
                    ],
                    "tags": {
                      "aggregatedBy": "max",
                      "name": "mongo-service-one-collection-two"
                    }
                  }
                ]"""
              )

      val expected = Seq(
        Metric(
          label     = "mongo-service-one-collection-one",
          value     = BigDecimal(1676590),
          timestamp = Instant.ofEpochSecond(1675606800)
        ),
        Metric(
          label     = "mongo-service-one-collection-two",
          value     = BigDecimal(79688835),
          timestamp = Instant.ofEpochSecond(1675606800)
        )
      )

      val response = connector.getCollectionSizes(
        environment = Environment.QA
      , database    = "service-one"
      , from        = Instant.ofEpochSecond(1675606000)
      , to          = Instant.ofEpochSecond(1675607000)
      ).futureValue

      response should contain theSameElementsAs expected
