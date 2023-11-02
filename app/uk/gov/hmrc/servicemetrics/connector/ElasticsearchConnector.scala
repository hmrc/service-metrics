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
)(implicit
  ec: ExecutionContext
) extends play.api.Logging {

  import ElasticsearchConnector._

  private val base64Encoder               = Base64.getEncoder()
  private val base64Decoder               = Base64.getDecoder()
  private val basicAuthenticationCredentials = elasticsearchConfig.environmentPasswords
                                              .map{ case (env, password) =>
                                                val decodedPassword = scala.util.Try(new String(base64Decoder.decode(password)).trim()).getOrElse {
                                                  logger.info(s"Couldn't decode password for env ${env.asString}")
                                                  ""
                                                }
                                                env -> s"Basic ${new String(base64Encoder.encode(s"${elasticsearchConfig.username}:$decodedPassword".getBytes()))}"
                                              }

  def getSlowQueries(environment: Environment, database: String)(implicit hc: HeaderCarrier): Future[Seq[MongoQueryLog]] =
    getMongoDbLogs(environment, s"duration:>${elasticsearchConfig.longRunningQueryInMilliseconds} AND database: $database")

  def getNonIndexedQueries(environment: Environment, database: String)(implicit hc: HeaderCarrier): Future[Seq[MongoQueryLog]] =
    getMongoDbLogs(environment, s"scan: COLLSCAN AND NOT mongo_db: backup_mongodb AND database: $database")

  private def getMongoDbLogs(environment: Environment, query: String)(implicit hc: HeaderCarrier): Future[Seq[MongoQueryLog]] = {
    val baseUrl = elasticsearchConfig.elasticSearchBaseUrl.replace("$env", environment.asString)

    implicit val mmr: Reads[Seq[MongoQueryLog]] = MongoQueryLog.reads

    val to   = Instant.now
    val from = to.minusSeconds(elasticsearchConfig.nonPerformantQueriesIntervalInMinutes*60)

    val body = s"""
    {
      "size": 10000,
      "query": {
        "bool": {
          "must": [
            {
              "query_string": {
                "query": "type:mongodb AND $query"
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
      "sort": [
        {
          "@timestamp": {
            "order": "desc"
          }
        }
      ]
    }"""

    httpClientV2
      .post(url"$baseUrl/${elasticsearchConfig.mongoDbIndex}/_search/")(hc.withExtraHeaders(
        "Authorization" -> basicAuthenticationCredentials(environment),
        "Content-Type"   -> "application/json",
      ))
      .withBody(body)
      .execute[JsValue]
      .map(json =>
        json.validate[Seq[MongoQueryLog]]
          .fold(error => {
              logger.error(s"Error while parsing JSON $json\n\n$error")
              Seq.empty
            },
            identity  
          )

      )
  }

}
object ElasticsearchConnector {
  case class MongoQueryLog(
    timestamp : Instant,
    collection: String,
    database  : String,
    mongoDb   : String,
    operation : Option[String],
    duration  : Int,
  )

  object MongoQueryLog{

    private implicit val readsLog: Reads[MongoQueryLog] =
      ( (__ \ "_source" \ "@timestamp").read[Instant]
      ~ (__ \ "_source" \ "collection").read[String]
      ~ (__ \ "_source" \ "database"  ).read[String]
      ~ (__ \ "_source" \ "mongo_db"  ).read[String]
      ~ (__ \ "_source" \ "operation" ).readNullable[String]
      ~ (__ \ "_source" \ "duration"  ).read[Int]
      )(MongoQueryLog.apply _)

    val reads: Reads[Seq[MongoQueryLog]] =
      (__ \ "hits" \ "hits").read[Seq[MongoQueryLog]]
  }
}

