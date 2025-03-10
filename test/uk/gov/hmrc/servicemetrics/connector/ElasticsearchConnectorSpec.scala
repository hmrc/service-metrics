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
    , "microservice.services.elasticsearch.username"                                   -> "changeme"
    , "microservice.services.elasticsearch.development.password"                       -> "Y2hhbmdlbWU="
    , "microservice.services.elasticsearch.integration.password"                       -> "Y2hhbmdlbWU="
    , "microservice.services.elasticsearch.qa.password"                                -> "Y2hhbmdlbWU="
    , "microservice.services.elasticsearch.staging.password"                           -> "Y2hhbmdlbWU="
    , "microservice.services.elasticsearch.externaltest.password"                      -> "Y2hhbmdlbWU="
    , "microservice.services.elasticsearch.production.password"                        -> "Y2hhbmdlbWU="
    , "microservice.services.elasticsearch.non-performant-queries-interval-in-minutes" -> 1440
    )
  private val now = Instant.now()

  private val connector = ElasticsearchConnector(ServicesConfig(config), httpClientV2)

  "search" should:
    "return logs" in:
        stubFor:
          post(urlPathEqualTo(s"/logstash-*/_search/"))
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
          .search(Environment.QA, dataView = "logstash-*", "message:\\\"some.log\\\"", now.minusSeconds(1000), now, "app.raw")
          .futureValue shouldBe Seq(
            SearchResult(
              key   = "app.raw"
            , count = 1
            )
          )

  "averageMongoDuration" should:
    "return mongo logs" in:
        stubFor:
          post(urlPathEqualTo(s"/logstash-*/_search/"))
            .willReturn:
              aResponse()
                .withStatus(200)
                .withBody("""
                  {
                    "took": 493,
                    "timed_out": false,
                    "_shards": {
                      "total": 737,
                      "successful": 737,
                      "skipped": 613,
                      "failed": 0
                    },
                    "hits": {
                      "total": {
                        "value": 10000,
                        "relation": "gte"
                      },
                      "max_score": null,
                      "hits": []
                    },
                    "aggregations": {
                      "database": {
                        "doc_count_error_upper_bound": 0,
                        "sum_other_doc_count": 314,
                        "buckets": [
                          {
                            "key": "some-database-1",
                            "doc_count": 5,
                            "collection": {
                              "doc_count_error_upper_bound": 0,
                              "sum_other_doc_count": 0,
                              "buckets": [
                                {
                                  "key": "some-collection-1",
                                  "doc_count": 2,
                                  "avg_duration": {
                                    "value": 6145.902085472786
                                  }
                                },
                                {
                                  "key": "some-collection-2",
                                  "doc_count": 3,
                                  "avg_duration": {
                                    "value": 3818.171965317919
                                  }
                                }
                              ]
                            }
                          },
                          {
                            "key": "some-database-2",
                            "doc_count": 9,
                            "collection": {
                              "doc_count_error_upper_bound": 0,
                              "sum_other_doc_count": 0,
                              "buckets": [
                                {
                                  "key": "some-collection-3",
                                  "doc_count": 9,
                                  "avg_duration": {
                                    "value": 16800.564043209877
                                  }
                                }
                              ]
                            }
                          }
                        ]
                      }
                    }
                  }"""
                )

        connector
          .averageMongoDuration(Environment.QA, dataView = "logstash-*", query = "some.raw:'Foo'", now.minusSeconds(1000), now)
          .futureValue shouldBe Map(
            "some-database-1" -> Seq(
                                    AverageMongoDuration(collection = "some-collection-1", occurrences = 2, avgDuration = 6145)
                                  , AverageMongoDuration(collection = "some-collection-2", occurrences = 3, avgDuration = 3818)
                                  )
          , "some-database-2" -> Seq(AverageMongoDuration(collection = "some-collection-3", occurrences = 9, avgDuration = 16800))
          )
