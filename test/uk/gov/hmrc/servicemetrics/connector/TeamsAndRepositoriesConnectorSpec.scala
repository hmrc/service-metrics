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
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.when
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.mockito.MockitoSugar
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.test.{HttpClientV2Support, WireMockSupport}
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig
import uk.gov.hmrc.servicemetrics.connector.TeamsAndRepositoriesConnector.Service

import scala.concurrent.ExecutionContext.Implicits.global

class TeamsAndRepositoriesConnectorSpec
  extends AnyWordSpec
     with Matchers
     with ScalaFutures
     with IntegrationPatience
     with HttpClientV2Support
     with WireMockSupport
     with MockitoSugar:

  given HeaderCarrier = HeaderCarrier()

  private val mockConfig: ServicesConfig = mock[ServicesConfig]

  when(mockConfig.baseUrl(any[String]))
    .thenReturn(wireMockUrl)

  private val connector = TeamsAndRepositoriesConnector(httpClientV2, mockConfig)

  "allServices" should:
    "return list of service names" in:
      stubFor:
        get(urlEqualTo("/api/v2/repositories?repoType=service"))
          .willReturn:
            aResponse()
              .withStatus(200)
              .withBody("""
                [
                  {
                    "name": "service-one",
                    "description": "",
                    "url": "https://github.com/hmrc/service-one",
                    "createdDate": "2018-11-07T10:54:02Z",
                    "lastActiveDate": "2022-01-11T17:10:23Z",
                    "isPrivate": true,
                    "repoType": "Service",
                    "owningTeams": [],
                    "language": "HTML",
                    "isArchived": false,
                    "defaultBranch": "main",
                    "branchProtection": {
                      "requiresApprovingReviews": false,
                      "dismissesStaleReviews": false,
                      "requiresCommitSignatures": true
                    },
                    "isDeprecated": false,
                    "teamNames": [
                      "Team One"
                    ]
                  },
                  {
                    "name": "service-two",
                    "description": "",
                    "url": "https://github.com/hmrc/service-one",
                    "createdDate": "2019-10-10T15:13:50Z",
                    "lastActiveDate": "2020-11-20T08:51:39Z",
                    "isPrivate": true,
                    "repoType": "Service",
                    "owningTeams": [],
                    "language": "HTML",
                    "isArchived": false,
                    "defaultBranch": "main",
                    "branchProtection": {
                      "requiresApprovingReviews": false,
                      "dismissesStaleReviews": false,
                      "requiresCommitSignatures": true
                    },
                    "isDeprecated": false,
                    "teamNames": [
                      "Team Two"
                    ]
                  }
                ]"""
              )

      val expected = Seq(
        Service(
          "service-one",
          Seq("Team One"),
          Seq.empty
        ),
        Service(
          "service-two",
          Seq("Team Two"),
          Seq.empty
        )
      )

      val response = connector.allServices().futureValue

      response should contain theSameElementsAs expected

  "findServices" should:
    "return all services of an owning team" in:
      stubFor:
        get(urlEqualTo(s"/api/v2/repositories?repoType=service&owningTeam=Team+One"))
        .willReturn:
          aResponse()
            .withStatus(200)
            .withBody("""
              [
                {
                  "name": "service-one",
                  "teamNames": [
                    "Team One"
                  ],
                  "digitalServices": []
                },
                {
                  "name": "service-two",
                  "teamNames": [
                    "Team One"
                  ],
                  "digitalServices": []
                }
              ]"""
            )

      val expected = Seq(
        Service(
          "service-one",
          Seq("Team One"),
          Seq.empty
        ),
        Service(
          "service-two",
          Seq("Team One"),
          Seq.empty
        )
      )

      val response = connector.findServices(Some("Team One"), None).futureValue
      response should contain theSameElementsAs expected

    "return all services of a digital service name" in:
      stubFor:
        get(urlEqualTo(s"/api/v2/repositories?repoType=service&digitalServiceName=Digital+One"))
        .willReturn:
          aResponse()
            .withStatus(200)
            .withBody("""
              [
                {
                  "name": "service-one",
                  "teamNames": [
                    "Team One"
                  ],
                  "digitalServices": [
                    "Digital One"
                  ]
                },
                {
                  "name": "service-two",
                  "teamNames": [
                    "Team One"
                  ],
                  "digitalServices": [
                    "Digital One"
                  ]
                }
              ]"""
            )

      val expected = Seq(
        Service(
          "service-one",
          Seq("Team One"),
          Seq("Digital One")
        ),
        Service(
          "service-two",
          Seq("Team One"),
          Seq("Digital One")
        )
      )

      val response = connector.findServices(None, Some("Digital One")).futureValue
      response should contain theSameElementsAs expected
