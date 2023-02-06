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

import play.api.libs.json.Json
import play.api.libs.ws.DefaultWSCookie
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.{HeaderCarrier, StringContextOps}
import uk.gov.hmrc.servicemetrics.model.MongoMetrics

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.io.Source

@Singleton
class GrafanaConnector @Inject()(httpClientV2 : HttpClientV2)(implicit ec: ExecutionContext) {

  def getMongoMetrics(environment: String)(implicit hc: HeaderCarrier): Future[Seq[MongoMetrics]] = {
//    implicit val mmR = MongoMetrics.reads
//    httpClientV2
//      .get(url"https://grafana.tools.qa.tax.service.gov.uk/api/datasources/proxy/48/render?target=groupByNode(collectd.*_mongo_*.mongo-*.file_size-data,2,'max')&from=now-1h&to=now&format=json&maxDataPoints=1")
//      .transform(_.addCookies(DefaultWSCookie("grafana_session", "COOKIE")))
//      .withProxy
//      .execute[Seq[MongoMetrics]]
//    val x = Source.fromResource("app/uk/gov/hmrc/servicemetrics/connector/mongo-metrics-qa.json").mkString
//    Future.successful(Json.parse(x).as[Seq[MongoMetrics]])

    val x = Source.fromFile("/Users/samhmrcdigital/Documents/mongo-metrics.json")
    val json = x.mkString
    x.close()
    Future.successful(Json.parse(json).as[Seq[MongoMetrics]])
  }
}



