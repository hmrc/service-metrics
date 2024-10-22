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
import org.scalatestplus.mockito.MockitoSugar
import play.api.Configuration
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.test.{HttpClientV2Support, WireMockSupport}
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig
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
    with MockitoSugar:

  import ElasticsearchConnector._

  private given HeaderCarrier = HeaderCarrier()

  private val config =
    Configuration(
      "microservice.services.elasticsearch.host"                                       -> wireMockHost
    , "microservice.services.elasticsearch.port"                                       -> wireMockPort
    , "microservice.services.elasticsearch.mongodb-index"                              -> "mongodb-logs"
    , "microservice.services.elasticsearch.username"                                   -> "changeme"
    , "microservice.services.elasticsearch.development.password"                       -> "Y2hhbmdlbWU="
    , "microservice.services.elasticsearch.integration.password"                       -> "Y2hhbmdlbWU="
    , "microservice.services.elasticsearch.qa.password"                                -> "Y2hhbmdlbWU="
    , "microservice.services.elasticsearch.staging.password"                           -> "Y2hhbmdlbWU="
    , "microservice.services.elasticsearch.externaltest.password"                      -> "Y2hhbmdlbWU="
    , "microservice.services.elasticsearch.production.password"                        -> "Y2hhbmdlbWU="
    , "microservice.services.elasticsearch.non-performant-queries-interval-in-minutes" -> 1440
    )

  private val mongoDbLogsIndex = "mongodb-logs"
  private val now              = Instant.now()

  private val connector = ElasticsearchConnector(ServicesConfig(config), httpClientV2)

  "search" should:
    "return logs" in:
        stubFor:
          post(urlPathEqualTo(s"/$mongoDbLogsIndex/_search/"))
            .willReturn:
              aResponse()
                .withStatus(200)
                .withBody("""
                  {
                    "took": 7,
                    "timed_out": false,
                    "_shards": {
                      "total": 5,
                      "successful": 5,
                      "skipped": 0,
                      "failed": 0
                    },
                    "hits": {
                      "total": 1,
                      "max_score": 3.321853,
                      "hits": []
                    },
                    "aggregations": {
                      "term-count": {
                        "doc_count_error_upper_bound": 0,
                        "sum_other_doc_count": 6,
                        "buckets": [
                          {
                            "key": "app.raw",
                            "doc_count": 1
                          }
                        ]
                      }
                    }
                  }"""
                )

        connector
          .search(Environment.QA, "tags.raw:\\\"UnsafeContent\\\"", now.minusSeconds(1000), now)
          .futureValue shouldBe Seq(
            SearchResult(
              key   = "app.raw"
            , count = 1
            )
          )

  "averageMongoDuration" should:
    "return mongo logs" in:
        stubFor:
          post(urlPathEqualTo(s"/$mongoDbLogsIndex/_search/"))
            .willReturn:
              aResponse()
                .withStatus(200)
                .withBody("""
                  {
                    "took": 7,
                    "timed_out": false,
                    "_shards": {
                      "total": 5,
                      "successful": 5,
                      "skipped": 0,
                      "failed": 0
                    },
                    "hits": {
                      "total": 1,
                      "max_score": 3.321853,
                      "hits": []
                    },
                    "aggregations": {
                      "mongo": {
                        "doc_count_error_upper_bound": 0,
                        "sum_other_doc_count": 6,
                        "buckets": [
                          {
                            "key": "some-collection-name",
                            "doc_count": 1,
                            "avg_duration": {
                              "value": 10121.309582309583
                            }
                          }
                        ]
                      }
                    }
                  }"""
                )

        connector
          .averageMongoDuration(Environment.QA, query = "some.raw:'Foo'", database = "some-db-name", now.minusSeconds(1000), now)
          .futureValue shouldBe Seq(
            AverageMongoDuration(
              collection  = "some-collection-name"
            , occurrences = 1
            , avgDuration = 10121
            )
          )
