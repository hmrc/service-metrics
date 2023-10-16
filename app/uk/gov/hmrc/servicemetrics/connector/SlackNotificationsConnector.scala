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

import com.google.common.io.BaseEncoding
import javax.inject.{Inject, Singleton}
import play.api.libs.json._
import uk.gov.hmrc.http.{HeaderCarrier, StringContextOps}
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.HttpReads.Implicits._
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig
import uk.gov.hmrc.servicemetrics.config.SlackNotifiactionsConfig

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class SlackNotificationsConnector @Inject()(
    httpClientV2  : HttpClientV2,
    servicesConfig: ServicesConfig,
    slackNotifiactionsConfig   : SlackNotifiactionsConfig
  )(implicit val ec: ExecutionContext) {

  private val url: String = servicesConfig.baseUrl("slack-notifications")
  private val authorizationHeaderValue =
    s"Basic ${BaseEncoding.base64().encode(s"${slackNotifiactionsConfig.username}:${slackNotifiactionsConfig.password}".getBytes("UTF-8"))}"

  def sendMessage(message: SlackNotificationRequest): Future[SlackNotificationResponse] = {
    implicit val hc         : HeaderCarrier = HeaderCarrier()
    implicit val snreqWrites: OWrites[SlackNotificationRequest] = SlackNotificationsFormats.snreqWrites
    implicit val snresReads : Reads[SlackNotificationResponse]  = SlackNotificationsFormats.snresReads
    httpClientV2
      .post(url"$url/slack-notifications/notification")
      .withBody(Json.toJson(message))
      .setHeader("Authorization" -> authorizationHeaderValue)
      .execute[SlackNotificationResponse]
  }
}

object SlackNotificationsFormats {

  val snreqWrites: OWrites[SlackNotificationRequest] = {
    implicit val clWrites: Writes[ChannelLookup] = Writes {
      case s: OwningTeams             => Json.toJson(s)(Json.writes[OwningTeams])
    }

    implicit val mdfWrites: OWrites[MessageDetails] = {
      Json.writes[MessageDetails]
    }

    Json.writes[SlackNotificationRequest]
  }

  val snresReads: Reads[SlackNotificationResponse] = {
    implicit val sneReads: Reads[SlackNotificationError] = Json.reads[SlackNotificationError]
    Json.reads[SlackNotificationResponse]
  }
}

final case class SlackNotificationError(
  code   : String,
  message: String
)

final case class SlackNotificationResponse(
  errors: List[SlackNotificationError]
)

sealed trait ChannelLookup { def by: String }

final case class OwningTeams(
  repositoryName: String,
  by            : String = "github-repository"
) extends ChannelLookup

final case class MessageDetails(
  text: String,
)

final case class SlackNotificationRequest(
  channelLookup : ChannelLookup,
  messageDetails: MessageDetails
)
