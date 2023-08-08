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

import play.api.libs.functional.syntax.toFunctionalBuilderOps
import play.api.libs.json.{Reads, __}
import uk.gov.hmrc.http.HttpReads.Implicits._
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.{HeaderCarrier, StringContextOps}
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig
import uk.gov.hmrc.servicemetrics.connector.CarbonApiConnector.MongoCollectionSizeMetric
import uk.gov.hmrc.servicemetrics.model.Environment

import java.time.Instant
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class CarbonApiConnector @Inject()(
  httpClientV2 : HttpClientV2
, servicesConfig: ServicesConfig
)(implicit
  ec: ExecutionContext
) {

  private val carbonApiBaseUrl: String = servicesConfig.baseUrl("carbon-api")

  def getCollectionSizes(environment: Environment, database: String)(implicit hc: HeaderCarrier): Future[Seq[MongoCollectionSizeMetric]] = {
    val baseUrl = carbonApiBaseUrl.replace("$env", environment.asString)

    implicit val mmr: Reads[MongoCollectionSizeMetric] = MongoCollectionSizeMetric.reads

    val to   = Instant.now()
    val from = to.minusSeconds(3600)

    httpClientV2
      .get(url"$baseUrl/render?target=groupByNode(collectd.*_mongo_*.mongo-$database-*.file_size-data,2,'max')&from=${from.getEpochSecond}&to=${to.getEpochSecond}&format=json&maxDataPoints=1")
      .execute[Seq[MongoCollectionSizeMetric]]
  }

}
object CarbonApiConnector {
  case class MongoCollectionSizeMetric(metricLabel: String, sizeBytes: BigDecimal, timestamp: Instant)

  object MongoCollectionSizeMetric {
    val reads: Reads[MongoCollectionSizeMetric] =
      ( (__ \ "target").read[String]
      ~ (__ \ "datapoints" \ 0 \ 0).read[BigDecimal]
      ~ (__ \ "datapoints" \ 0 \ 1).read[Long].map(Instant.ofEpochSecond)
      )(apply _)
  }
}

