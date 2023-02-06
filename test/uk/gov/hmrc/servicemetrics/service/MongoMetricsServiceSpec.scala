package uk.gov.hmrc.servicemetrics.service

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.hmrc.servicemetrics.connector.GrafanaConnector
import org.mockito.scalatest.MockitoSugar
import uk.gov.hmrc.servicemetrics.model.{Datapoint, MongoCollectionSize, MongoMetrics}

import java.time.LocalDate

class MongoMetricsServiceSpec extends AnyWordSpec with Matchers with MockitoSugar {

  private val servicesLongestFirst = Seq("service-frontend", "service")

  private val service = new MongoMetricsService(mock[GrafanaConnector])

  "transform" should {
    "return MongoCollectionSize for a service" in {

      val metric = MongoMetrics("mongo-service-collection-one", Seq(Datapoint(BigDecimal(1000), 1675353600)))

      val expected = MongoCollectionSize("service", "collection-one", BigDecimal(1000), LocalDate.of(2023, 2, 2))

      service.transform(metric, servicesLongestFirst) shouldBe Some(expected)

    }

    "return None when has no collection name" in {

      val metric = MongoMetrics("mongo-service", Seq(Datapoint(BigDecimal(1000), 1675353600)))

      service.transform(metric, servicesLongestFirst) shouldBe None
    }


  }

}
