package uk.gov.hmrc.servicemetrics.persistence

import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository
import uk.gov.hmrc.servicemetrics.model.MongoCollectionSize

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext

@Singleton
class MongoCollectionSizeRepository @Inject()(
                                       mongoComponent: MongoComponent
                                     )(implicit ec: ExecutionContext
                                     ) extends PlayMongoRepository(
  collectionName = "mongoCollectionSizes",
  mongoComponent = mongoComponent,
  domainFormat   = MongoCollectionSize.format,
  indexes        = Seq()
){

}
