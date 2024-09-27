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
import uk.gov.hmrc.servicemetrics.config.ElasticsearchConfig
import uk.gov.hmrc.servicemetrics.model.Environment

import java.time.Instant
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import java.util.Base64

@Singleton
class ElasticsearchConnector @Inject()(
  httpClientV2 : HttpClientV2
, elasticsearchConfig: ElasticsearchConfig
)(using
  ExecutionContext
) extends play.api.Logging:

  import ElasticsearchConnector._

  private val basicAuthenticationCredentials =
    elasticsearchConfig.environmentPasswords
      .map: (env, password) =>
        val decodedPassword =
          String(Base64.getDecoder.decode(password)).trim // trim required since passwords were created with trailing \n
        env -> s"Basic ${String(Base64.getEncoder.encode(s"${elasticsearchConfig.username}:$decodedPassword".getBytes()))}"

  def getSlowQueries(environment: Environment, database: String, from: Instant, to: Instant)(using HeaderCarrier): Future[Option[MongoQueryLog]] =
    getMongoDbLogs(
      environment,
      s"duration:>${elasticsearchConfig.longRunningQueryInMilliseconds}",
      database,
      from,
      to
    )

  def getNonIndexedQueries(environment: Environment, database: String, from: Instant, to: Instant)(using HeaderCarrier): Future[Option[MongoQueryLog]] =
    getMongoDbLogs(
      environment,
      "scan: COLLSCAN",
      database,
      from,
      to
    )

  private def getMongoDbLogs(environment: Environment, query: String, database: String, from: Instant, to: Instant)(using HeaderCarrier): Future[Option[MongoQueryLog]] =
    given Reads[Seq[MongoCollectionNonPerformantQuery]] = MongoCollectionNonPerformantQuery.reads

    val body = Json.parse(s"""
      {
        "size": 0,
        "query": {
          "bool": {
            "must": [
              {
                "query_string": {
                  "query": "type:mongodb AND NOT mongo_db:(\\\"backup_mongo\\\"|\\\"backup_protected-mongo\\\"|\\\"backup_protected-auth-mongo\\\"|\\\"backup_protected-centralised-auth-mongo\\\"|\\\"backup_protected-rate-mongo\\\"|\\\"backup_public-mongo\\\") AND $query AND  database.raw:\\\"$database\\\""
                }
              }
            ],
            "filter": [
              {
                "range": {
                  "@timestamp": {
                    "format": "strict_date_optional_time",
                    "gte": "$from",
                    "lte": "$to"
                  }
                }
              }
            ]
          }
        },
        "aggs": {
          "collections": {
            "terms": { "field": "collection.raw" },
            "aggs": {
              "avg_duration" : { "avg" : { "field" : "duration" } }
            }
          }
        },
        "sort": [
          {
            "@timestamp": {
              "order": "desc"
            }
          }
        ]
      }""")

    val url = url"${elasticsearchConfig.elasticSearchBaseUrl.replace("$env", environment.asString)}/${elasticsearchConfig.mongoDbIndex}/_search/"

    httpClientV2
      .post(url)
      .setHeader:
        "Authorization" -> basicAuthenticationCredentials(environment)
      .withBody(body)
      .execute[Seq[MongoCollectionNonPerformantQuery]]
      .recover: e =>
        logger.error(s"Error getting mongo db logs for query '$query' from $url: ${e.getMessage}", e)
        Seq.empty
      .map: nonPerformantQueries =>
        Option.when(nonPerformantQueries.nonEmpty):
          MongoQueryLog(
            since                 = from,
            timestamp             = to,
            nonPerformantQueries  = nonPerformantQueries,
            database              = database,
          )


object ElasticsearchConnector:

  case class MongoCollectionNonPerformantQuery(
    collection : String,
    occurrences: Int,
    duration   : Int,
  )

  object MongoCollectionNonPerformantQuery:
    val reads: Reads[Seq[MongoCollectionNonPerformantQuery]] =
      given Reads[MongoCollectionNonPerformantQuery] =
        ( (__ \ "key"                   ).read[String]
        ~ (__ \ "doc_count"             ).read[Int]
        ~ (__ \ "avg_duration" \ "value").read[Double].map(_.toInt)
        )(apply)
      (__ \ "aggregations" \ "collections" \ "buckets").read[Seq[MongoCollectionNonPerformantQuery]]

  case class MongoQueryLog(
    since               : Instant,
    timestamp           : Instant,
    nonPerformantQueries: Seq[MongoCollectionNonPerformantQuery],
    database            : String,
  )
