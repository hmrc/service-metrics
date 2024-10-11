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
import uk.gov.hmrc.mongo.lock.{LockService, MongoLockRepository}
import uk.gov.hmrc.servicemetrics.model.Environment
import uk.gov.hmrc.servicemetrics.service.MongoMetricsService

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal

@Singleton
class MongoMetricsScheduler @Inject()(
  configuration      : Configuration
, lockRepository     : MongoLockRepository
, mongoMetricsService: MongoMetricsService
)(using
  ActorSystem
, ApplicationLifecycle
, ExecutionContext
) extends SchedulerUtils:

  override val logger = Logger(getClass)

  private given HeaderCarrier = HeaderCarrier()

  private val schedulerConfig: SchedulerConfig =
    SchedulerConfig(configuration, "mongo-metrics-scheduler")

  scheduleWithLock(
    label           = "MongoMetricsScheduler"
  , schedulerConfig = schedulerConfig
  , lock            = LockService(lockRepository, "mongo-metrics-scheduler", schedulerConfig.interval)
  ):
    val envs: List[Environment] =
      Environment.values.toList.filterNot(_.equals(Environment.Integration))
    logger.info(s"Updating mongo metrics for ${envs.mkString(", ")}")
    for
      _ <- envs.foldLeftM(())((_, env) => updatePerEnvironment(env))
    yield logger.info(s"Finished updating mongo metrics for ${envs.mkString(", ")}")

  private def updatePerEnvironment(env: Environment)(using HeaderCarrier) =
    for
      dbMappings <- mongoMetricsService.dbMappings(env)
      _          <- mongoMetricsService
                      .updateCollectionSizes(env, dbMappings)
                      .recover:
                        case NonFatal(e) => logger.error(s"Failed to update mongo collection sizes for ${env.asString}", e)
      _          <- mongoMetricsService
                      .insertQueryLogs(env, dbMappings)
                      .recover:
                        case NonFatal(e) => logger.error(s"Failed to insert mongo query logs for ${env.asString}", e)
    yield ()
