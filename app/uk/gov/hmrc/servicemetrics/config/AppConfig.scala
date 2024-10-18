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

import javax.inject.{Inject, Singleton}
import play.api.Configuration
import java.net.URLEncoder

@Singleton
class AppConfig @Inject()(config: Configuration):

  val collectionSizesHistoryFrequencyDays: Int =
    config.get[Int]("mongo-collection-size-history.frequency.days")

  private val longRunningQueryInMilliseconds: Int =
    config.get[Int]("microservice.services.elasticsearch.long-running-query-in-milliseconds")

  import AppConfig.{LogMetric, LogMetricId, LogConfigType}
  val logMetrics: List[LogMetric] =
    LogMetric(LogMetricId.SlowRunningQuery, "Slow Running Query", LogConfigType.AverageMongoDuration(s"duration:>${longRunningQueryInMilliseconds}"), config.get[String]("alerts.slack.kibana.links.slow-running-query")) ::
    LogMetric(LogMetricId.NonIndexedQuery , "Non-indexed Query" , LogConfigType.AverageMongoDuration("scan:COLLSCAN")                               , config.get[String]("alerts.slack.kibana.links.non-indexed-query") ) ::
    LogMetric(LogMetricId.UnsafeContent   , "Unsafe Content"    , LogConfigType.GenericSearch("tags.raw:'UnsafeContent'")                           , config.get[String]("alerts.slack.kibana.links.unsafe-content")    ) ::
    Nil

  def findLogMetricById(logMetricId: LogMetricId): LogMetric =
    logMetrics.find(_.id == logMetricId) match
      case None    => sys.error(s"Configuration issue - could not find ${logMetricId.asString}")
      case Some(x) => x

  import uk.gov.hmrc.servicemetrics.persistence.LogHistoryRepository
  def createMessage(team: String, logs: Seq[LogHistoryRepository.LogHistory]): Seq[String] =
    logs
      .groupBy(_.logType)
      .foldLeft(Seq( s"Hi *$team*, PlatOps would like to notify you about the following Kibana logs:")):
        case (acc, (logType: LogHistoryRepository.LogType.GenericSearch, ns)) =>
          acc :+
            ns.sortBy(x => (x.service, x.environment))
              .map:
                case n =>
                  val logMetric = findLogMetricById(logType.logMetricId)
                  val link      = logMetric
                                    .kibanaLink
                                    .replace(s"$${env}"    , URLEncoder.encode(n.environment.asString, "UTF-8"))
                                    .replace(s"$${service}", URLEncoder.encode(n.service             , "UTF-8"))
                  s"• service *${n.service}* in *${n.environment.asString}* has ${logMetric.displayName} - <$link|see kibana>"
              .distinct
              .mkString("\n")
        case (acc, (logType: LogHistoryRepository.LogType.AverageMongoDuration, ns)) =>
          acc :+
            ns.sortBy(x => (x.service, x.environment))
              .flatMap:
                case n => n.logType.asInstanceOf[LogHistoryRepository.LogType.AverageMongoDuration].details.map(detail => (n, detail)) // TODO :-(
              .map:
                case (n, detail) =>
                  val logMetric = findLogMetricById(logType.logMetricId)
                  val link      = logMetric
                                    .kibanaLink
                                    .replace(s"$${env}"     , URLEncoder.encode(n.environment.asString, "UTF-8"))
                                    .replace(s"$${database}", URLEncoder.encode(detail.database       , "UTF-8"))
                  s"• service *${n.service}* in *${n.environment.asString}* has ${logMetric.displayName} - <$link|see kibana>"
              .distinct
              .mkString("\n")


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
    case UnsafeContent    extends LogMetricId("unsafe-content"    )

  case class LogMetric(
    id         : LogMetricId
  , displayName: String
  , logType    : LogConfigType
  , kibanaLink : String
  )

  enum LogConfigType(val query: String):
    case GenericSearch(       override val query: String) extends LogConfigType(query)
    case AverageMongoDuration(override val query: String) extends LogConfigType(query)




