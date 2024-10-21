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

package uk.gov.hmrc.servicemetrics.persistence

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport
import uk.gov.hmrc.servicemetrics.config.AppConfig
import uk.gov.hmrc.servicemetrics.model.Environment

import java.time.Instant
import scala.concurrent.ExecutionContext.Implicits.global

class LogHistoryRepositorySpec
  extends AnyWordSpec
     with Matchers
     with DefaultPlayMongoRepositorySupport[LogHistoryRepository.LogHistory]:

  override val repository: LogHistoryRepository =
    LogHistoryRepository(mongoComponent)

  private val now = Instant.now.truncatedTo(java.time.temporal.ChronoUnit.MILLIS)

  "find" should:
    "return results" when:
      "there are slow running mongo logs" in:
        val item =
          LogHistoryRepository.LogHistory(
            timestamp   = now
          , since       = now.minusSeconds(20)
          , service     = "service"
          , logType     = LogHistoryRepository.LogType.AverageMongoDuration(
                            AppConfig.LogMetricId.SlowRunningQuery
                          , Seq(
                              LogHistoryRepository.LogType.AverageMongoDuration.MongoDetails(
                                database    = "database"
                              , collection  = "collection"
                              , duration    = 3001
                              , occurrences = 1
                              )
                            )
                          )
          , environment = Environment.QA
          , teams       = Seq("team")
          )

        repository.insertMany(Seq(item)).futureValue

        repository.find(
          from = now.minusSeconds(10)
        , to   = now
        ).futureValue should be (Seq(item))

  "find" should:
    "return results" when:
      "there are unsafe-content logs for a service" in:
        val item =
          LogHistoryRepository.LogHistory(
            timestamp   = now
          , since       = now.minusSeconds(20)
          , service     = "service"
          , logType     = LogHistoryRepository.LogType.GenericSearch(
                            AppConfig.LogMetricId.UnsafeContent
                          , details = 5
                          )
          , environment = Environment.QA
          , teams       = Seq("team")
          )

        repository.insertMany(Seq(item)).futureValue

        repository.find(
          service = Some("service")
        , from    = now.minusSeconds(10)
        , to      = now
        ).futureValue should be (Seq(item))
