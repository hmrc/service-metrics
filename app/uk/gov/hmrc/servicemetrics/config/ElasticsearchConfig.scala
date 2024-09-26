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

import play.api.Configuration
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig
import uk.gov.hmrc.servicemetrics.model.Environment

import javax.inject.{Inject, Singleton}

@Singleton
class ElasticsearchConfig @Inject() (
  configuration : Configuration,
  servicesConfig: ServicesConfig
):

  val elasticSearchBaseUrl: String =
    servicesConfig.baseUrl("elasticsearch")

  val username: String =
    configuration.get[String]("microservice.services.elasticsearch.username")

  val environmentPasswords: Map[Environment, String] =
    Environment.values
      .map: env =>
        env -> configuration.get[String](s"microservice.services.elasticsearch.${env.asString}.password")
      .toMap

  val mongoDbIndex: String =
    configuration.get[String]("microservice.services.elasticsearch.mongodb-index")

  val longRunningQueryInMilliseconds: Int =
    configuration.get[Int]("microservice.services.elasticsearch.long-running-query-in-milliseconds")
