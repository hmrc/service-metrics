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

import javax.inject.{Inject, Singleton}
import play.api.libs.json._
import play.api.libs.functional.syntax._
import play.api.libs.ws.writeableOf_JsValue
import uk.gov.hmrc.http.{HeaderCarrier, StringContextOps}
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.HttpReads.Implicits._
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig
import uk.gov.hmrc.servicemetrics.config.SlackNotificationsConfig

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class SlackNotificationsConnector @Inject()(
  httpClientV2            : HttpClientV2,
  servicesConfig          : ServicesConfig,
  slackNotifiactionsConfig: SlackNotificationsConfig
)(using ExecutionContext):

  private val url: String = servicesConfig.baseUrl("slack-notifications")

  def sendMessage(message: SlackNotificationRequest)(using HeaderCarrier): Future[SlackNotificationResponse] =
    given Writes[SlackNotificationRequest] = SlackNotificationsFormats.snreqWrites
    given Reads[SlackNotificationResponse] = SlackNotificationsFormats.snresReads
    httpClientV2
      .post(url"$url/slack-notifications/v2/notification")
      .withBody(Json.toJson(message))
      .setHeader("Authorization" -> slackNotifiactionsConfig.authToken)
      .setHeader("Content-type"  -> "application/json")
      .execute[SlackNotificationResponse]

object SlackNotificationsFormats:

  val snreqWrites: Writes[SlackNotificationRequest] =
    given Writes[ChannelLookup] = Writes {
      case s: OwningTeams   => Json.toJson(s)(Json.writes[OwningTeams])
      case s: SlackChannels => Json.toJson(s)(Json.writes[SlackChannels])
      case s: GithubTeam    => Json.toJson(s)(Json.writes[GithubTeam])
    }

    ( (__ \ "channelLookup").write[ChannelLookup]
    ~ (__ \ "displayName"  ).write[String]
    ~ (__ \ "emoji"        ).write[String]
    ~ (__ \ "text"         ).write[String]
    ~ (__ \ "blocks"       ).write[Seq[JsValue]]
    )(o => Tuple.fromProductTyped(o))

  val snresReads: Reads[SlackNotificationResponse] =
    given Reads[SlackNotificationError] =
      ( (__ \ "code"   ).read[String]
      ~ (__ \ "message").read[String]
      )(SlackNotificationError.apply)

    (__ \ "errors")
      .readWithDefault[List[SlackNotificationError]](List.empty)
      .map(SlackNotificationResponse.apply)

case class SlackNotificationError(
  code   : String,
  message: String
)

case class SlackNotificationResponse(
  errors: List[SlackNotificationError]
)

sealed trait ChannelLookup { def by: String }

case class GithubTeam(
  teamName: String,
  by      : String = "github-team"
) extends ChannelLookup

case class OwningTeams(
  repositoryName: String,
  by            : String = "github-repository"
) extends ChannelLookup

case class SlackChannels(
  slackChannels: Seq[String],
  by           : String = "slack-channel"
) extends ChannelLookup

case class SlackNotificationRequest(
  channelLookup: ChannelLookup,
  displayName  : String,
  emoji        : String,
  text         : String,
  blocks       : Seq[JsValue]
)

object SlackNotificationRequest:
  def toBlocks(messages: Seq[String]): Seq[JsValue] =
    Json.parse("""{"type": "divider"}"""") +:
      messages.map: message =>
        Json.parse(s"""{
          "type": "section",
          "text": {
            "type": "mrkdwn",
            "text": "${message.replace("\n","\\n")}"
          }
        }""")
