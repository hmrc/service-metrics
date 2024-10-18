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
import play.api.libs.json.Json
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.test.{HttpClientV2Support, WireMockSupport}
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig

import scala.concurrent.ExecutionContext.Implicits.global

class SlackNotificationsConnectorSpec
  extends AnyWordSpec
     with Matchers
     with ScalaFutures
     with IntegrationPatience
     with HttpClientV2Support
     with WireMockSupport
     with MockitoSugar:

  given HeaderCarrier = HeaderCarrier()

  private lazy val connector =
    SlackNotificationsConnector(
      ServicesConfig(Configuration(
        "microservice.services.slack-notifications.port" -> wireMockPort
      , "microservice.services.slack-notifications.host" -> wireMockHost
      , "alerts.slack.auth-token"                        -> "changeme"
      ))
    , httpClientV2
    )

  "allServices" should:
    "return list of service names" in:
      stubFor:
        post(urlEqualTo("/slack-notifications/v2/notification"))
          .willReturn:
            aResponse()
              .withStatus(200)
              .withBody("""{"errors": []}""")

      val message = SlackNotificationsConnector.Request(
        channelLookup = SlackNotificationsConnector.ChannelLookup.GithubTeam("team1"),
        displayName   = "displayName",
        emoji         = "emoji",
        text          = "text",
        blocks        = Seq(
                          Json.parse("""{"type": "divider"}""""),
                          Json.parse(s"""{
                            "type": "section",
                            "text": {
                              "type": "mrkdwn",
                              "text": "message"
                            }
                          }""")
                        )
      )

      val response = connector.sendMessage(message).futureValue

      response shouldBe ()

      verify(
        postRequestedFor(urlEqualTo("/slack-notifications/v2/notification"))
           .withRequestBody(equalToJson("""{
             "channelLookup": {
               "by"      : "github-team",
               "teamName": "team1"
             },
             "displayName": "displayName",
             "emoji"      : "emoji",
             "text"       : "text",
             "blocks"     : [ {
                 "type" : "divider"
               }, {
                 "type" : "section",
                 "text" : {
                   "type" : "mrkdwn",
                   "text" : "message"
                 }
               } ]
             }"""))
      )
