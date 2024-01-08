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

import org.apache.pekko.actor.ActorSystem
import play.api.Logger
import play.api.inject.ApplicationLifecycle
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.mongo.lock.{LockService, MongoLockRepository}
import uk.gov.hmrc.servicemetrics.config.SchedulerConfigs
import uk.gov.hmrc.servicemetrics.model.Environment
import uk.gov.hmrc.servicemetrics.service.MongoService

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.DurationInt
import scala.util.control.NonFatal

@Singleton
class MongoMetricsScheduler @Inject()(
  schedulerConfig     : SchedulerConfigs
, lockRepository      : MongoLockRepository
, mongoMetricsService : MongoService
)(implicit
  actorSystem          : ActorSystem
, applicationLifecycle : ApplicationLifecycle
, ec                   : ExecutionContext
) extends SchedulerUtils {

  override val logger = Logger(getClass)

  scheduleWithLock(
    label           = "MongoMetricsScheduler",
    schedulerConfig = schedulerConfig.mongoMetricsScheduler,
    lock            = LockService(lockRepository, "mongo-metrics-scheduler", 30.minutes)
  ) {
    val envs: List[Environment] =
      Environment.values.filterNot(_.equals(Environment.Integration))

    logger.info(s"Updating mongo metrics for ${envs.mkString(", ")}")
    implicit val hc: HeaderCarrier = HeaderCarrier()
    for {
      _ <- Future.traverse(envs)(updatePerEnvironment)
    } yield logger.info(s"Finished updating mongo metrics for ${envs.mkString(", ")}")
  }

  private def updatePerEnvironment(env: Environment)(implicit hc: HeaderCarrier) =
    for {
      _ <- mongoMetricsService
            .updateCollectionSizes(env)
            .recoverWith {
              case NonFatal(e) =>
                logger.error(s"Failed to update mongo collection sizes for ${env.asString}", e)
                Future.unit
            }
      _ <- if (schedulerConfig.collectNonPerfomantQueriesEnabled)
              mongoMetricsService
             .insertQueryLogs(env)
             .recoverWith {
               case NonFatal(e) =>
                 logger.error(s"Failed to insert mongo query logs for ${env.asString}", e)
                 Future.unit
             }
           else
             Future.unit
    } yield ()
}
