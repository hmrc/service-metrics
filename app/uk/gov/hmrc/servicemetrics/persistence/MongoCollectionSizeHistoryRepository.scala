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

import org.mongodb.scala.bson.BsonDocument
import org.mongodb.scala.model.{Filters, IndexModel, IndexOptions, Indexes}
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository
import uk.gov.hmrc.servicemetrics.model.{Environment, MongoCollectionSize}

import java.time.LocalDate
import java.util.concurrent.TimeUnit
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class MongoCollectionSizeHistoryRepository @Inject()(
  mongoComponent: MongoComponent
)(implicit
  ec: ExecutionContext
) extends PlayMongoRepository(
  mongoComponent = mongoComponent,
  collectionName = MongoCollectionSizeHistoryRepository.collectionName,
  domainFormat   = MongoCollectionSize.mongoFormat,
  indexes        = MongoCollectionSizeHistoryRepository.indexes
) {

  def find(
    service    : Option[String]      = None,
    environment: Option[Environment] = None,
    date       : Option[LocalDate]   = None
  ): Future[Seq[MongoCollectionSize]] = {

    val filters = Seq(
      service.map(s => Filters.equal("service", s)),
      environment.map(env => Filters.equal("environment", env.asString)),
      date.map(d => Filters.equal("date", d))
    ).flatten

    collection.find(
      filter = if (filters.isEmpty) BsonDocument() else Filters.and(filters: _*)
    ).toFuture()

  }

  def historyExists(environment: Environment, afterDate: LocalDate): Future[Boolean] = {
    collection
      .find(
        Filters.and(
          Filters.equal("environment", environment.asString),
          Filters.gt("date", afterDate)
        )
      )
      .limit(1)
      .toFuture()
      .map(_.nonEmpty)
  }

  def insertMany(mcs: Seq[MongoCollectionSize]): Future[Unit] =
    collection.insertMany(mcs).toFuture().map(_ => ())
}

object MongoCollectionSizeHistoryRepository {
  val collectionName = "mongoCollectionSizesHistory"

  val indexes: Seq[IndexModel] =
    Seq(
      IndexModel(Indexes.ascending("service")),
      IndexModel(Indexes.ascending("environment")),
      IndexModel(Indexes.ascending("date"), IndexOptions().expireAfter(90, TimeUnit.DAYS))
//      IndexModel(
//        Indexes.compoundIndex(
//          Indexes.ascending("database"),
//          Indexes.ascending("collection"),
//          Indexes.ascending("environment"),
//          Indexes.ascending("date")
//        ),
//        IndexOptions().unique(true).background(true)
//      )
    )
}
