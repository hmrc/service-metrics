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
import org.mockito.Mockito.when
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.mockito.MockitoSugar
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.test.{HttpClientV2Support, WireMockSupport}
import uk.gov.hmrc.servicemetrics.config.ClickHouseConfig
import uk.gov.hmrc.servicemetrics.model.Environment

import scala.concurrent.ExecutionContext.Implicits.global

class ClickHouseConnectorSpec
  extends AnyWordSpec
     with Matchers
     with ScalaFutures
     with HttpClientV2Support
     with WireMockSupport
     with MockitoSugar:

  private given HeaderCarrier = HeaderCarrier()

  private val mockConfig = mock[ClickHouseConfig]

  when(mockConfig.urls)
    .thenReturn:
      Environment.values
        .filterNot(_ == Environment.Integration)
        .map(env => env -> wireMockUrl)
        .toMap

  val connector = ClickHouseConnector(httpClientV2, mockConfig)

  "getDatabaseNames" should:
    "return a list of database names" in:
      stubFor:
        get(urlEqualTo("/latest/mongodbs"))
          .willReturn:
            aResponse()
              .withStatus(200)
              .withBody("""
                {
                  "name": ["database-one", "database-two", "database-three"]
                }
                """
              )

      val expected = Seq("database-one", "database-two", "database-three")

      val response = connector.getDatabaseNames(Environment.QA).futureValue

      response should contain theSameElementsAs expected
