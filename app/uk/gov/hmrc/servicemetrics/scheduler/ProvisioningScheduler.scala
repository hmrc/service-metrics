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

package uk.gov.hmrc.servicemetrics.scheduler

import cats.implicits._
import org.apache.pekko.actor.ActorSystem
import play.api.{Configuration, Logger}
import play.api.inject.ApplicationLifecycle
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.mongo.TimestampSupport
import uk.gov.hmrc.mongo.lock.{ScheduledLockService, MongoLockRepository}
import uk.gov.hmrc.servicemetrics.connector.ReleasesApiConnector
import uk.gov.hmrc.servicemetrics.model.Environment
import uk.gov.hmrc.servicemetrics.service.MetricsService

import javax.inject.{Inject, Singleton}
import java.time.{Instant, LocalDate, LocalTime, ZoneOffset}
import java.time.temporal.TemporalAdjusters
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

@Singleton
class ProvisioningScheduler @Inject()(
  configuration       : Configuration
, lockRepository      : MongoLockRepository
, timestampSupport    : TimestampSupport
, metricsService      : MetricsService
, releasesApiConnector: ReleasesApiConnector
)(using
  ActorSystem
, ApplicationLifecycle
, ExecutionContext
) extends SchedulerUtils:

  override val logger = Logger(getClass)

  private given HeaderCarrier = HeaderCarrier()

  private val schedulerConfig: SchedulerConfig =
    SchedulerConfig(configuration, "scheduler.provisioning")

  private def month(instant: Instant) =
    LocalDate.ofInstant(instant, ZoneOffset.UTC).getMonthValue

  scheduleWithLock(
    label           = "Provisioning Scheduler"
  , schedulerConfig = schedulerConfig
  , lock            = ScheduledLockService(lockRepository, "provisioning-scheduler", timestampSupport, schedulerConfig.interval)
  ):
    val envs = Environment.applicableValues
    logger.info(s"Updating provisioning for ${envs.mkString(", ")}")
    for
      wrw     <- releasesApiConnector.whatsRunningWhere()
      lastRun <- lockRepository
                   .collection
                   .find(org.mongodb.scala.model.Filters.equal(uk.gov.hmrc.mongo.lock.Lock.id, "provisioning-scheduler"))
                   .headOption
                   .map(_.map(_.timeCreated))
      _       <- if   lastRun.fold(-1)(month) == month(Instant.now)
                 then Future.successful(logger.info("Not updating provisioning metrics - already have last month's data"))
                 else
                  val t1   = LocalDate.now.minusMonths(1)
                  val to   = t1.`with`(TemporalAdjusters.firstDayOfMonth).atStartOfDay(ZoneOffset.UTC).toInstant
                  val from = t1.`with`(TemporalAdjusters.lastDayOfMonth ).atTime(LocalTime.MIDNIGHT  ).toInstant(ZoneOffset.UTC)
                  envs.map:
                    env => (env -> wrw.collect { case x if x.deployments.exists(_.environment == env) => x.serviceName })
                  .foldLeftM(()):
                    case (_, (env, services)) =>
                      metricsService
                        .insertProvisioningMetrics(env, from = from, to = to, services)
                        .recover:
                          case NonFatal(e) => logger.error(s"Failed to insert provisioning metrics for ${env.asString}", e)
    yield logger.info(s"Finished updating provisioning metrics for ${envs.mkString(", ")}")

