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
import uk.gov.hmrc.servicemetrics.model.Environment

import javax.inject.{Inject, Singleton}
import scala.collection.immutable.TreeMap
import scala.concurrent.duration.Duration
import play.api.libs.json.JsValue
import uk.gov.hmrc.servicemetrics.connector.SlackNotificationsConnector

@Singleton
class AppConfig @Inject()(config: Configuration):

  val collectionSizesHistoryFrequency: Duration =
    config.get[Duration]("mongo-collection-size-history.frequency")

  private val longRunningQueryThreshold: Duration =
    config.get[Duration]("long-running-query-threshold")

  import AppConfig.{LogMetric, LogMetricId, LogConfigType}
  val logMetrics: TreeMap[LogMetricId, LogMetric] =
    TreeMap(
      LogMetricId.SlowRunningQuery -> LogMetric(
                                        displayName   = "Slow Running Query"
                                      , logType       = LogConfigType.AverageMongoDuration(s"duration:>${longRunningQueryThreshold.toMillis}")
                                      , rawKibanaLink = config.get[String]("alerts.slack.kibana.links.slow-running-query")
                                      )
    , LogMetricId.NonIndexedQuery  -> LogMetric(
                                        displayName   = "Non-indexed Query"
                                      , logType       = LogConfigType.AverageMongoDuration("scan:COLLSCAN")
                                      , rawKibanaLink = config.get[String]("alerts.slack.kibana.links.non-indexed-query")
                                      )
    , LogMetricId.OrphanToken       -> LogMetric(
                                         displayName = "Orphaned internal-auth token"
                                       , logType     = LogConfigType.GenericSearch("app.raw: \\\"internal-auth\\\" AND level.raw: \\\"WARN\\\" AND \\\"An orphaned token exists for principal:\\\"")
                                       , rawKibanaLink = config.get[String]("alerts.slack.kibana.links.orphan-token")
                                       )
    // , LogMetricId.UnsafeContent    -> LogMetric(
    //                                     displayName   = "Unsafe Content"
    //                                   , logType       = LogConfigType.GenericSearch("tags.raw:\\\"UnsafeContent\\\"")
    //                                   , rawKibanaLink = config.get[String]("alerts.slack.kibana.links.unsafe-content")
    //                                   , onlyNotifyIn  = Seq(Environment.Production)
    //                                   )
    )

  import uk.gov.hmrc.servicemetrics.persistence.LogHistoryRepository
  def createMessage(team: String, logMetricId: LogMetricId, logs: Seq[LogHistoryRepository.LogHistory]): Seq[JsValue] =
    val logMetric = logMetrics(logMetricId)
    logMetric.logType match
      case _: LogConfigType.AverageMongoDuration =>
        SlackNotificationsConnector.mrkdwnBlock(s"Hi *$team*, you have the following *${logMetric.displayName}* Kibana logs:") +:
        logs.sortBy(x => (x.service, x.environment))
          .flatMap: n =>
            n.logType.asInstanceOf[LogHistoryRepository.LogType.AverageMongoDuration].details.map(detail => (n, detail))
          .map: (n, detail) =>
            val link = kibanaLink(logMetric, n.service, n.environment, Some(detail.database))
            SlackNotificationsConnector.mrkdwnBlock(s"• service *${n.service}* in *${n.environment.displayString}* for collection *${detail.collection}* - <${link}|see kibana>")
          .distinct
      case _: LogConfigType.GenericSearch =>
        SlackNotificationsConnector.mrkdwnBlock(s"Hi *$team*, you have the following *${logMetric.displayName}* Kibana logs:") +:
        logs.sortBy(x => (x.service, x.environment))
          .map: n =>
            val link = kibanaLink(logMetric, n.service, n.environment)
            SlackNotificationsConnector.mrkdwnBlock(s"• service *${n.service}* in *${n.environment.displayString}* - <$link|see kibana>")
          .distinct

  import java.net.URLEncoder
  def kibanaLink(logMetric: LogMetric, serviceName: String, environment: Environment, oDatabase: Option[String] = None): String =
    (logMetric.logType, oDatabase) match
      case (_: AppConfig.LogConfigType.AverageMongoDuration, Some(database)) =>
        logMetric
          .rawKibanaLink
          .replace(s"$${env}"     , URLEncoder.encode(environment.asString, "UTF-8"))
          .replace(s"$${database}", URLEncoder.encode(database            , "UTF-8"))
      case (_: AppConfig.LogConfigType.GenericSearch, None) =>
        logMetric
          .rawKibanaLink
          .replace(s"$${env}"    , URLEncoder.encode(environment.asString, "UTF-8"))
          .replace(s"$${service}", URLEncoder.encode(serviceName         , "UTF-8"))
      case _ =>
        sys.error(s"Bad inputs to create kibana link logType: ${logMetric.logType} serviceName: $serviceName environment: ${environment.asString}, oDatabase: $oDatabase")

object AppConfig:
  import play.api.libs.json.{Reads, Writes}
  import play.api.mvc.{PathBindable, QueryStringBindable}
  import uk.gov.hmrc.servicemetrics.util.{FromString, FromStringEnum, Parser}

  import FromStringEnum._

  given Parser[LogMetricId] = Parser.parser(LogMetricId.values)

  enum LogMetricId(
    override val asString: String
  ) extends FromString
    derives Ordering, Reads, Writes, PathBindable, QueryStringBindable:
    case SlowRunningQuery extends LogMetricId("slow-running-query")
    case NonIndexedQuery  extends LogMetricId("non-indexed-query" )
    case OrphanToken      extends LogMetricId("orphan-token"      )
    case UnsafeContent    extends LogMetricId("unsafe-content"    )

  case class LogMetric(
    displayName  : String
  , logType      : LogConfigType
  , rawKibanaLink: String
  , onlyNotifyIn : Seq[Environment] = Environment.applicableValues
  )

  enum LogConfigType(val query: String):
    case GenericSearch(       override val query: String) extends LogConfigType(query)
    case AverageMongoDuration(override val query: String) extends LogConfigType(query)
