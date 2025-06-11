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
      env     = environment
    , targets = s"groupByNode(collectd.*_mongo_*.mongo-$database-*.file_size-data,2,'max')" :: Nil
    , from    = from
    , to      = to
    )

  def getProvisioningMetrics(
    environment: Environment
  , service    : String
  , from       : Instant
  , to         : Instant
  )(using HeaderCarrier): Future[Seq[Metric]] =
    getMetric(
      env     = environment
    , targets = s"alias(summarize(aggregate(aggregates.$service.*.upstream_rq_[2-5][0-9][0-9].sum, 'sum'), '1mon', 'sum', false), 'requests')"                                                                   ::
                s"alias(summarize(aggregate(aggregates.$service.*.upstream_rq_time.mean.avg, 'average'), '1mon', 'average', false), 'time', false)"                                                              ::
                s"alias(summarize(aggregate(container-insights.*-mdtp.*$service*.Container.$service.*.memory-reserved, 'count'), '1mon', 'avg', false), 'instances')"  /* counts per instance of metric */       ::
                s"alias(summarize(scale(sumSeries(container-insights.*-mdtp.*$service*.Container.$service.*.memory-reserved), 7.450581e-9), '1mon', 'avg', false), 'slots')"  /* 1 / (128 * 1024 * 1024) */      ::
                s"alias(maximumAbove(group(aliasByNode(container-insights.*-mdtp.*$service*.Container.$service.*.memory-utilized,5),aliasByNode(container-insights.*.$service.memory-utilized,1)),0), 'memory')" ::
                Nil
    , from    = from
    , to      = to
    ).map: xs =>
      xs ++ xs.filter(_.label == "memory").maxByOption(_.value)

  private def getMetric(env: Environment, targets: Seq[String], from: Instant, to: Instant)(using HeaderCarrier): Future[Seq[Metric]] =
    given Reads[Metric] = Metric.reads
    httpClientV2
      .get(url"${carbonApiBaseUrl.replace("$env", env.asString)}/render?target=$targets&from=${from.getEpochSecond}&until=${to.getEpochSecond}&format=json&maxDataPoints=1")
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
