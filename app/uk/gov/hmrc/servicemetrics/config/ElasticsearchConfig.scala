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

import uk.gov.hmrc.play.bootstrap.config.ServicesConfig
import uk.gov.hmrc.servicemetrics.model.Environment

import javax.inject.{Inject, Singleton}

@Singleton
class ElasticsearchConfig @Inject() (
  servicesConfig: ServicesConfig
) {

  lazy val elasticSearchBaseUrl                 : String                   = servicesConfig.baseUrl("elasticsearch")
  lazy val username                             : String                   = servicesConfig.getString("microservice.services.elasticsearch.username")
  lazy val environmentPasswords                 : Map[Environment, String] = Environment.values.map(env =>
                                                                              env -> servicesConfig.getString(s"microservice.services.elasticsearch.password.${env.asString}")
                                                                             ).toMap
  lazy val mongoDbIndex                         : String                   = servicesConfig.getString("microservice.services.elasticsearch.mongodb-index")
  lazy val longRunningQueryInMilliseconds       : Int                      = servicesConfig.getInt("microservice.services.elasticsearch.long-running-query-in-milliseconds")
  lazy val nonPerformantQueriesIntervalInMinutes: Int                      = servicesConfig.getInt("microservice.services.elasticsearch.non-performant-queries-interval-in-minute")

}
