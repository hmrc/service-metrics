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

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import uk.gov.hmrc.servicemetrics.model.Environment

import java.time.Instant

class AppConfigSpec
  extends AnyWordSpec
    with Matchers
    with GuiceOneAppPerSuite:

  "AppConfig" should:
      val appConfig           = AppConfig(app.configuration)
      val nonIndexedLogMetric = appConfig.logMetrics(AppConfig.LogMetricId.NonIndexedQuery)
      "return the correct LogMetric for NonIndexedQuery" in:
        nonIndexedLogMetric.displayName shouldBe "Non-indexed Query"
        nonIndexedLogMetric.logType shouldBe a[AppConfig.LogConfigType.AverageMongoDuration]
        nonIndexedLogMetric.rawKibanaLink should include ("${env}")
        nonIndexedLogMetric.rawKibanaLink should include ("${database}")
        nonIndexedLogMetric.rawKibanaLink should include ("${from}")
        nonIndexedLogMetric.rawKibanaLink should include ("${to}")

      "return the correct query filter for NonIndexedQuery" in:
        val logType = nonIndexedLogMetric.logType
        logType shouldBe a[AppConfig.LogConfigType.AverageMongoDuration]

        logType match {
          case AppConfig.LogConfigType.AverageMongoDuration(query) =>
            query should include("scan:COLLSCAN")
            // .comment('some-string') result in provided string being included in 'operation' in kibana
            query should include ("AND NOT operation: \\\"no-index-required\\\"")

            // 'lsid' refers to the logical session id of the query, which is added by mongo driver.
            // The presence of this field indicates that the query was executed by a service using the mongo driver
            //  rather than any other tooling / monitoring in the mongodb infrastructure.
            query should include ("AND operation: \\\"lsid\\\"")
          case _ =>
            fail("Expected LogConfigType.AverageMongoDuration")
        }



