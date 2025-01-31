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

import java.time.Instant
import java.time.temporal.ChronoUnit

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
    , LogMetricId.OrphanToken      -> LogMetric(
                                        displayName     = "Orphaned internal-auth token"
                                      , logType         = LogConfigType.GenericSearch("app.raw: \\\"internal-auth\\\" AND level.raw: \\\"WARN\\\" AND \\\"An orphaned token exists for principal:\\\"")
                                      , rawKibanaLink   = config.get[String]("alerts.slack.kibana.links.orphan-token")
                                      , showInCatalogue = false // PlatOps Slack notification is all thats required
                                      )
    , LogMetricId.ContainerKills   -> LogMetric(
                                        displayName   = "Container Kills"
                                      , logType       = LogConfigType.GenericSearch("kill_date_time: *")
                                      , rawKibanaLink = config.get[String]("alerts.slack.kibana.links.container-kills")
                                      , dataView      = "logstash-container_kills*"
                                      , keyword       = "service.keyword"
                                      , onlyNotifyIn  = Nil // Teams are already sent a Pager Duty
                                      )
    )

  import uk.gov.hmrc.servicemetrics.persistence.LogHistoryRepository
  def createMessage(team: String, logMetricId: LogMetricId, logs: Seq[LogHistoryRepository.LogHistory]): Seq[JsValue] =
    val logMetric = logMetrics(logMetricId)
    logMetric.logType match
      case _: LogConfigType.AverageMongoDuration =>
        SlackNotificationsConnector.mrkdwnBlock(s"Hi *$team*, you have the following *${logMetric.displayName}* Kibana logs:") +:
        logs
          .flatMap: n =>
            n.logType.asInstanceOf[LogHistoryRepository.LogType.AverageMongoDuration].details.map(detail => (n, detail))
          .distinctBy( (n, detail) => (n.service, n.environment, detail.collection))
          .sortBy( (n, detail) => (n.service, n.environment, detail.collection))
          .map: (n, detail) =>
            val link = kibanaLink(logMetric, n.service, n.environment, Some(detail.database))
            SlackNotificationsConnector.mrkdwnBlock(s"• service *${n.service}* in *${n.environment.displayString}* for collection *${detail.collection}* - <${link}|see kibana>")
      case _: LogConfigType.GenericSearch =>
        SlackNotificationsConnector.mrkdwnBlock(s"Hi *$team*, you have the following *${logMetric.displayName}* Kibana logs:") +:
        logs
          .distinctBy(x => (x.service, x.environment))
          .sortBy(x => (x.service, x.environment))
          .map: n =>
            val link = kibanaLink(logMetric, n.service, n.environment)
            SlackNotificationsConnector.mrkdwnBlock(s"• service *${n.service}* in *${n.environment.displayString}* - <$link|see kibana>")

  import java.net.URLEncoder
  def kibanaLink(
    logMetric  : LogMetric
  , serviceName: String
  , environment: Environment
  , oDatabase  : Option[String]  = None
  , from       : Option[Instant] = Some(Instant.now().minus(7, ChronoUnit.DAYS))
  , to         : Option[Instant] = Some(Instant.now()) // default is 1 day
  ): String =
    (logMetric.logType, oDatabase, from, to) match
      case (_: AppConfig.LogConfigType.AverageMongoDuration, Some(database), Some(from), Some(to)) =>
        logMetric
          .rawKibanaLink
          .replace(s"$${env}"     , URLEncoder.encode(environment.asString, "UTF-8"))
          .replace(s"$${database}", URLEncoder.encode(database            , "UTF-8"))
          .replace(s"$${from}"    , URLEncoder.encode(from.toString       , "UTF-8"))
          .replace(s"$${to}"      , URLEncoder.encode(to.toString         , "UTF-8"))
      case (_: AppConfig.LogConfigType.GenericSearch, _, Some(from), Some(to)) =>
        logMetric
          .rawKibanaLink
          .replace(s"$${env}"    , URLEncoder.encode(environment.asString, "UTF-8"))
          .replace(s"$${service}", URLEncoder.encode(serviceName         , "UTF-8"))
           .replace(s"$${from}"  , URLEncoder.encode(from.toString       , "UTF-8"))
           .replace(s"$${to}"    , URLEncoder.encode(to.toString         , "UTF-8"))
      case _ =>
        sys.error(s"Bad inputs to create kibana link logType: ${logMetric.logType} serviceName: $serviceName environment: ${environment.asString}, oDatabase: $oDatabase, from: $from, to: $to")

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
    case ContainerKills   extends LogMetricId("container-kills"   )

  case class LogMetric(
    displayName    : String
  , logType        : LogConfigType
  , rawKibanaLink  : String
  , dataView       : String           = "logstash-*"
  , keyword        : String           = "app.raw"
  , onlyNotifyIn   : Seq[Environment] = Environment.applicableValues
  , showInCatalogue: Boolean          = true
  )

  enum LogConfigType(val query: String):
    case GenericSearch(       override val query: String) extends LogConfigType(query)
    case AverageMongoDuration(override val query: String) extends LogConfigType(query)
