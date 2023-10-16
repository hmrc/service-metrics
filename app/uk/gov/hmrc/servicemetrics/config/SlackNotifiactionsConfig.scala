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

package uk.gov.hmrc.servicemetrics.config

import javax.inject.{Inject, Singleton}
import play.api.Configuration

@Singleton
class SlackNotifiactionsConfig @Inject()(configuration: Configuration) {
  private val slackKey   = "alerts.slack.basicAuth"
  val username: String   = configuration.get[String](s"$slackKey.username")
  val password: String   = configuration.get[String](s"$slackKey.password")
  val enabled : Boolean  = configuration.get[Boolean](s"alerts.slack.enabled")

  val kibanaLinks: Map[String, String] = configuration.get[Map[String, String]]("alerts.slack.kibana.links")
}
