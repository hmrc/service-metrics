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
import play.api.libs.json._
import play.api.libs.ws.writeableOf_JsValue
import uk.gov.hmrc.http.HttpReads.Implicits._
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.{HeaderCarrier, StringContextOps}
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig
import uk.gov.hmrc.servicemetrics.model.Environment

import java.time.Instant
import javax.inject.{Inject, Singleton}
import java.util.Base64
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ElasticsearchConnector @Inject()(
  servicesConfig: uk.gov.hmrc.play.bootstrap.config.ServicesConfig
, httpClientV2  : HttpClientV2
)(using
  ExecutionContext
) extends play.api.Logging:

  import ElasticsearchConnector._

  private val baseUrl: String =
    servicesConfig.baseUrl("elasticsearch")

  private val username: String =
    servicesConfig.getString("microservice.services.elasticsearch.username")

  private val authForEnv: Map[Environment, String] =
    Environment
      .applicableValues
      .map: env =>
        env -> servicesConfig.getString(s"microservice.services.elasticsearch.${env.asString}.password")
      .map: (env, password) =>
        val decodedPassword = String(Base64.getDecoder.decode(password)).trim // trim required since passwords were created with trailing \n
        env -> s"Basic ${String(Base64.getEncoder.encode(s"$username:$decodedPassword".getBytes()))}"
      .toMap

  def search(environment: Environment, dataView: String, query: String, from: Instant, to: Instant, keyword: String)(using HeaderCarrier): Future[Seq[SearchResult]] =
    given Reads[Seq[SearchResult]] = SearchResult.reads
    val url  = url"${baseUrl.replace("$env", environment.asString)}/$dataView/_search/"

    httpClientV2
      .post(url)
      .setHeader:
        "Authorization" -> authForEnv(environment)
      .withBody(Json.parse(s"""
        { "size": 0,
          "query": {
            "bool": {
              "must": [
                { "query_string": { "query": "$query" } }
              ],
              "filter": [
                { "range": { "@timestamp": { "format": "strict_date_optional_time", "gte": "$from", "lte": "$to" } } }
              ]
            }
          },
          "aggs": {
            "term-count": {
              "terms": { "field": "$keyword" }
            }
          }
        }""")
      )
      .execute[Seq[SearchResult]]
      .recover: e =>
        logger.error(s"Error searching query '$query' from $url: ${e.getMessage}", e)
        Nil

  def averageMongoDuration(environment: Environment, dataView: String, query: String, from: Instant, to: Instant)(using HeaderCarrier): Future[Map[String, Seq[AverageMongoDuration]]] =
    given Reads[Map[String, Seq[AverageMongoDuration]]] = AverageMongoDuration.reads
    val url = url"${baseUrl.replace("$env", environment.asString)}/$dataView/_search/"

    httpClientV2
      .post(url)
      .setHeader:
        "Authorization" -> authForEnv(environment)
      .withBody(Json.parse(s"""
        { "size": 0,
          "query": {
            "bool": {
              "must": [
                { "query_string": { "query": "type:mongodb AND NOT mongo_db:(\\\"backup_mongo\\\"|\\\"backup_protected-mongo\\\"|\\\"backup_protected-auth-mongo\\\"|\\\"backup_protected-centralised-auth-mongo\\\"|\\\"backup_protected-rate-mongo\\\"|\\\"backup_public-mongo\\\") AND $query" } }
              ],
              "filter": [
                { "range": { "@timestamp": { "format": "strict_date_optional_time", "gte": "$from", "lte": "$to" } } }
              ]
            }
          },
          "aggs": {
            "database": {
              "terms": { "field": "database.raw" },
              "aggs": {
                "collection": {
                  "terms": { "field": "collection.raw" },
                  "aggs": {
                    "avg_duration" : { "avg" : { "field" : "duration" } }
                  }
                }
              }
            }
          },
          "sort": [
            {  "@timestamp": { "order": "desc" } }
          ]
        }""")
      )
      .execute[Map[String, Seq[AverageMongoDuration]]]
      .recover: e =>
        logger.error(s"Error getting average mongo duration for query '$query' from $url: ${e.getMessage}", e)
        Map.empty

object ElasticsearchConnector:

  case class AverageMongoDuration(
    collection : String,
    occurrences: Int,
    avgDuration: Int,
  )

  object AverageMongoDuration:
    val reads: Reads[Map[String, Seq[AverageMongoDuration]]] =
      given Reads[AverageMongoDuration] =
        ( (__ \ "key"                   ).read[String]
        ~ (__ \ "doc_count"             ).read[Int]
        ~ (__ \ "avg_duration" \ "value").read[Double].map(_.toInt)
        )(apply)

      given Reads[Tuple2[String, Seq[AverageMongoDuration]]] =
        ( (__ \ "key"                   ).read[String]
        ~ (__ \ "collection" \ "buckets").read[Seq[AverageMongoDuration]]
        )(Tuple2[String, Seq[AverageMongoDuration]].apply)

      (__ \ "aggregations" \ "database" \ "buckets")
        .read[Seq[Tuple2[String, Seq[AverageMongoDuration]]]]
        .map(_.toMap)

  case class SearchResult(key: String, count: Int)

  object SearchResult:
    val reads: Reads[Seq[SearchResult]] =
      given Reads[SearchResult] =
        ( (__ \ "key"      ).read[String]
        ~ (__ \ "doc_count").read[Int]
        )(apply)
      (__ \ "aggregations" \ "term-count" \ "buckets").read[Seq[SearchResult]]
