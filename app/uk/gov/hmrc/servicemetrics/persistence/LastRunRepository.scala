/*
 * Copyright 2025 HM Revenue & Customs
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

import org.mongodb.scala.bson.BsonDocument
import org.mongodb.scala.model.ReplaceOptions
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository
import uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats

import javax.inject.{Inject, Singleton}
import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class LastRunRepository @Inject()(
  mongoComponent: MongoComponent
)(implicit
  ec: ExecutionContext
) extends PlayMongoRepository[LastRunRepository.LastRun](
  mongoComponent = mongoComponent,
  collectionName = "lastRun",
  domainFormat   = LastRunRepository.format,
  indexes        = Seq.empty
):

  override lazy val requiresTtlIndex = false // lastRun is just a single row thats updated, so doesn't rely on ttl indexes

  def getLastRun(): Future[Option[Instant]] =
    collection
      .find(BsonDocument())
      .headOption()
      .map(_.map(_.time))

  def setLastRun(lastRun: Instant): Future[Unit] =
    collection
      .replaceOne(
        filter      = BsonDocument(),
        replacement = LastRunRepository.LastRun(lastRun),
        options     = ReplaceOptions().upsert(true)
      )
      .toFuture()
      .map(_ => ())

object LastRunRepository:
  import play.api.libs.json.{Format, __}
  import play.api.libs.functional.syntax._

  case class LastRun(time: Instant) extends AnyVal

  implicit val format: Format[LastRun] =
    given Format[Instant] = MongoJavatimeFormats.instantFormat
    Format
      .at[Instant](__ \ "time")
      .inmap(LastRun.apply, _.time)
