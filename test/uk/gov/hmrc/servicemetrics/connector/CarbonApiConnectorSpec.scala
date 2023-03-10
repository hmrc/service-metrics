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

import com.github.tomakehurst.wiremock.client.WireMock.{aResponse, get, stubFor, urlEqualTo}
import org.mockito.scalatest.MockitoSugar
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.test.{HttpClientV2Support, WireMockSupport}
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig
import uk.gov.hmrc.servicemetrics.connector.CarbonApiConnector.{DatabaseName, MongoCollectionSizeMetric}
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
    with MockitoSugar {

  private implicit val hc: HeaderCarrier = HeaderCarrier()

  private val mockConfig: ServicesConfig = mock[ServicesConfig]

  when(mockConfig.baseUrl(any[String])).thenReturn(wireMockUrl)

  val connector = new CarbonApiConnector(httpClientV2, mockConfig)

  "getMongoMetrics" should {
    "return mongo metrics" in {

      val rawResponse =
        """
          |[
          |  {
          |    "target": "mongo-service-one-collection-one",
          |    "datapoints": [
          |      [
          |        1676590,
          |        1675606800
          |      ]
          |    ],
          |    "tags": {
          |      "aggregatedBy": "max",
          |      "name": "mongo-service-one-collection-one"
          |    }
          |  },
          |  {
          |    "target": "mongo-service-one-collection-two",
          |    "datapoints": [
          |      [
          |        79688835,
          |        1675606800
          |      ]
          |    ],
          |    "tags": {
          |      "aggregatedBy": "max",
          |      "name": "mongo-service-one-collection-two"
          |    }
          |  }
          |]""".stripMargin

      stubFor(
        get(urlEqualTo("/render?target=groupByNode(collectd.*_mongo_*.mongo-*.file_size-data,2,'max')&from=now-1h&to=now&format=json&maxDataPoints=1"))
          .willReturn(
            aResponse()
              .withStatus(200)
              .withBody(rawResponse)
          )
      )

      val expected = Seq(
        MongoCollectionSizeMetric(
          metricLabel = "mongo-service-one-collection-one",
          sizeBytes   = BigDecimal(1676590),
          timestamp   = Instant.ofEpochSecond(1675606800)
        ),
        MongoCollectionSizeMetric(
          metricLabel = "mongo-service-one-collection-two",
          sizeBytes   = BigDecimal(79688835),
          timestamp   = Instant.ofEpochSecond(1675606800)
        )
      )

      val response = connector.getMongoMetrics(Environment.QA).futureValue

      response should contain theSameElementsAs expected
    }
  }

  "getDatabaseNames" should {
    "return a list of database names" in {

      val rawResponse =
        """
          |[
          |  {
          |    "allowChildren": 1,
          |    "expandable": 1,
          |    "leaf": 0,
          |    "id": "collectd.*_mongo_*.mongo-usage.service-one",
          |    "text": "service-one",
          |    "context": {}
          |  },
          |  {
          |    "allowChildren": 1,
          |    "expandable": 1,
          |    "leaf": 0,
          |    "id": "collectd.*_mongo_*.mongo-usage.service-two",
          |    "text": "service-two",
          |    "context": {}
          |  }
          |]""".stripMargin

      stubFor(
        get(urlEqualTo("/metrics/find?query=collectd.*_mongo_*.mongo-usage.*&from=now-1h&until=now"))
          .willReturn(
            aResponse()
              .withStatus(200)
              .withBody(rawResponse)
          )
      )

      val expected = Seq(
        DatabaseName("service-one"),
        DatabaseName("service-two")
      )

      val response = connector.getDatabaseNames(Environment.QA).futureValue

      response should contain theSameElementsAs expected
    }
  }
}
