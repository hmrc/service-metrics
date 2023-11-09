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
import com.typesafe.config.ConfigFactory
import org.mockito.scalatest.MockitoSugar
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.Configuration
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.test.{HttpClientV2Support, WireMockSupport}
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig
import uk.gov.hmrc.servicemetrics.config.ElasticsearchConfig
import uk.gov.hmrc.servicemetrics.model.Environment

import java.time.Instant
import scala.concurrent.ExecutionContext.Implicits.global

class ElasticsearchConnectorSpec
  extends AnyWordSpec
    with Matchers
    with ScalaFutures
    with IntegrationPatience
    with HttpClientV2Support
    with WireMockSupport
    with MockitoSugar {

  import ElasticsearchConnector._

  private implicit val hc: HeaderCarrier = HeaderCarrier()
  private val servicesConfig   = new ServicesConfig(
    Configuration(ConfigFactory.parseString(s"""
    |microservice {
    |  services {
    |    elasticsearch {
    |      host                                       = "$wireMockHost"
    |      port                                       = $wireMockPort
    |      mongodb-index                              = "mongodb-logs"
    |      username                                   = "changeme"
    |      development.password                       = "Y2hhbmdlbWU="
    |      integration.password                       = "Y2hhbmdlbWU="
    |      qa.password                                = "Y2hhbmdlbWU="
    |      staging.password                           = "Y2hhbmdlbWU="
    |      externaltest.password                      = "Y2hhbmdlbWU="
    |      production.password                        = "Y2hhbmdlbWU="
    |      long-running-query-in-milliseconds         = 3000
    |      non-performant-queries-interval-in-minutes = 1440
    |    }
    |  }
    |}
    """.stripMargin))
  )

  private val mongoDbLogsIndex = "mongodb-logs"
  private val mongoDbDatabase  = "preferences"
  private val now              = Instant.now()

  val connector = new ElasticsearchConnector(httpClientV2, new ElasticsearchConfig(servicesConfig))

  "getMongoDbLogs" should {
    "return mongo logs" in {

        val rawResponse =
          s"""
            |{
            |  "took": 7,
            |  "timed_out": false,
            |  "_shards": {
            |    "total": 5,
            |    "successful": 5,
            |    "skipped": 0,
            |    "failed": 0
            |  },
            |  "hits": {
            |    "total": 1,
            |    "max_score": 3.321853,
            |    "hits": []
            |  },
            |  "aggregations": {
            |    "collections": {
            |      "doc_count_error_upper_bound": 0,
            |      "sum_other_doc_count": 6,
            |      "buckets": [
            |        {
            |          "key": "preferences",
            |          "doc_count": 1,
            |          "duration": {
            |            "value": 10121.309582309583
            |          }
            |        }
            |      ]
            |    }
            |  }
            |}
            |""".stripMargin

        stubFor(
          post(urlPathEqualTo(s"/$mongoDbLogsIndex/_search/"))
            .willReturn(
              aResponse()
                .withStatus(200)
                .withBody(rawResponse)
            )
        )

        val expectedResult = MongoQueryLog(
            since                = now,
            timestamp            = now,
            database             = mongoDbDatabase,
            nonPerformantQueries = Seq(MongoCollectionNonPerfomantQuery(
              collection  = "preferences",
              occurrences = 1,
              duration    = 10121,
            ))
          )

        val mongoDbLog = connector.getSlowQueries(Environment.QA, "preferences", now.minusSeconds(1000), now).futureValue.head

        mongoDbLog.database shouldBe expectedResult.database
        mongoDbLog.nonPerformantQueries should contain theSameElementsAs expectedResult.nonPerformantQueries
    }
  }
}
