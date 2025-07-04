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
import uk.gov.hmrc.servicemetrics.model.Environment

import java.time.Instant
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class CarbonApiConnector @Inject()(
  httpClientV2  : HttpClientV2
, servicesConfig: ServicesConfig
)(using
  ExecutionContext
):
  import uk.gov.hmrc.servicemetrics.connector.CarbonApiConnector.Metric

  private val carbonApiBaseUrl: String = servicesConfig.baseUrl("carbon-api")

  def getCollectionSizes(
    environment: Environment
  , database   : String
  , from       : Instant
  , to         : Instant
  )(using HeaderCarrier): Future[Seq[Metric]] =
    getMetric(
      env           = environment
    , targets       = s"groupByNode(collectd.*_mongo_*.mongo-$database-*.file_size-data,2,'max')" :: Nil
    , from          = from
    , to            = to
    , maxDataPoints = Some(1) // One data point per collection
    )

  // To test ssh into service-metrics. To get 1 months data add a target from below replacing $service
  // curl -G 'http://see-app-config-base/render?&from=1746057600&until=1748735999&format=json' --data-urlencode "target="
  def getServiceProvisionMetrics(
    environment: Environment
  , service    : String
  , from       : Instant
  , to         : Instant
  )(using HeaderCarrier): Future[Map[String, BigDecimal]] =
    def byteToMebibyte(bd: BigDecimal): BigDecimal = bd / BigDecimal(1048576)

    getMetric(
      env           = environment
    , targets       = s"alias(summarize(aggregate(aggregates.$service.*.upstream_rq_[2-5][0-9][0-9].sum, 'sum'), '1year', 'sum', false), 'requests')"                               :: // 1month returns multiple buckets so using 1year
                      s"alias(summarize(aggregate(aggregates.$service.*.upstream_rq_time.mean.avg, 'average'), '1year', 'average', false), 'time')"                                 ::
                      s"alias(summarize(aggregate(container-insights.*-mdtp.*$service*.Container.$service.*.memory-reserved, 'count'), '1year', 'average', false), 'instances')"    :: // counts per instance of metric
                      s"alias(summarize(scale(sumSeries(container-insights.*-mdtp.*$service*.Container.$service.*.memory-reserved), 7.450581e-9), '1year', 'avg', false), 'slots')" :: // (128 * 1024 * 1024)
                      s"alias(summarize(aggregate(container-insights.*-mdtp.*$service*.Container.$service.*.memory-utilized, 'max'), '1year', 'max', false) , 'memory')"            :: // max memory in byte for instance
                      Nil
    , from          = from
    , to            = to
    , maxDataPoints = None // Need to set to a high number (like 1000) or not at all otherwise values are "consolidated"  https://graphite.readthedocs.io/en/latest/render_api.html#maxdatapoints
    ).map:
      case xs if xs.nonEmpty => Map("requests" -> BigDecimal(0)) ++ xs.map(x => x.label -> x.value).toMap.updatedWith("memory")(_.map(byteToMebibyte))
      case _                 => Map.empty

  private def getMetric(
    env          : Environment
  , targets      : Seq[String]
  , from         : Instant
  , to           : Instant
  , maxDataPoints: Option[Int]
  )(using HeaderCarrier): Future[Seq[Metric]] =
    given Reads[Metric] = Metric.reads
    httpClientV2
      .get(url"${carbonApiBaseUrl.replace("$env", env.asString)}/render?target=$targets&from=${from.getEpochSecond}&until=${to.getEpochSecond}&format=json&maxDataPoints=$maxDataPoints")
      .execute[Seq[Metric]]

object CarbonApiConnector:

  case class Metric(
    label    : String,
    value    : BigDecimal,
    timestamp: Instant
  )

  object Metric:
    val reads: Reads[Metric] =
      ( (__ \ "target"            ).read[String]
      ~ (__ \ "datapoints" \ 0 \ 0).read[BigDecimal]
      ~ (__ \ "datapoints" \ 0 \ 1).read[Long].map(Instant.ofEpochSecond)
      )(apply)
