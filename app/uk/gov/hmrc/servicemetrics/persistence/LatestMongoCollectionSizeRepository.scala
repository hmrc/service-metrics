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

import org.mongodb.scala.model.{Filters, IndexModel, IndexOptions, Indexes}
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository
import uk.gov.hmrc.mongo.transaction.{TransactionConfiguration, Transactions}
import uk.gov.hmrc.servicemetrics.model.{Environment, MongoCollectionSize}

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class LatestMongoCollectionSizeRepository @Inject()(
  override val mongoComponent: MongoComponent
)(implicit
  ec: ExecutionContext
) extends PlayMongoRepository(
  mongoComponent = mongoComponent,
  collectionName = LatestMongoCollectionSizeRepository.collectionName,
  domainFormat   = MongoCollectionSize.mongoFormat,
  indexes        = LatestMongoCollectionSizeRepository.indexes
) with Transactions {

  // all records are deleted before inserting fresh on schedule
  override lazy val requiresTtlIndex: Boolean = false

  private implicit val tc: TransactionConfiguration = TransactionConfiguration.strict

  def find(service: String, environment: Option[Environment] = None): Future[Seq[MongoCollectionSize]] = {

    val filters = Seq(
      Some(Filters.equal("service", service)),
      environment.map(env => Filters.equal("environment", env.asString))
    ).flatten

    collection.find(Filters.and(filters:_*)).toFuture()

  }

  def putAll(mcs: Seq[MongoCollectionSize], environment: Environment): Future[Unit] =
    withSessionAndTransaction { session =>
      for {
        _ <- collection.deleteMany(session, Filters.equal("environment", environment.asString)).toFuture()
        _ <- collection.insertMany(session, mcs).toFuture()
      } yield ()
    }
}

object LatestMongoCollectionSizeRepository {
  val collectionName = "latestMongoCollectionSizes"

  val indexes: Seq[IndexModel] =
    Seq(
      IndexModel(Indexes.ascending("service"), IndexOptions().name("mcsServiceIdx").sparse(true)),
      IndexModel(Indexes.ascending("environment"), IndexOptions().name("mcsEnvironmentIdx"))
    )
}
