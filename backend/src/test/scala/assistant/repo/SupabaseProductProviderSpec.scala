package assistant.repo

import assistant.domain.ExtractedFilters
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import upickle.default._

class SupabaseProductProviderSpec extends AnyFunSuite with Matchers {

  private val dummyConfig = assistant.config.AppConfig(
    jwtSecret = "secret",
    jwtExpiresInHours = 1L,
    supabaseUrl = "http://localhost",
    supabaseKey = "key",
    resendApiKey = "",
    emailFrom = "",
    frontendUrl = "",
    gemmaApiKey = ""
  )

  class CapturingRestClient extends SupabaseRestClient(dummyConfig) {
    var lastTable: String = ""
    var lastParams: Map[String, String] = Map.empty
    var responseToReturn: String = "[]"

    override def get(table: String, params: Map[String, String]): String = {
      lastTable = table
      lastParams = params
      responseToReturn
    }
  }

  test("search constructs plfts search_vector and price=lte parameter when budget is present") {
    val client = new CapturingRestClient
    val provider = new SupabaseProductProvider(client)

    val filters = ExtractedFilters(
      category = Some("Footwear"),
      budget = Some(BigDecimal("120.00")),
      keywords = List("hiking", "waterproof"),
      attributes = Map("color" -> "black")
    )

    provider.search(filters, limit = 15)

    client.lastTable shouldBe "products"
    client.lastParams("category") shouldBe "not.is.null"
    client.lastParams("price") shouldBe "lte.120.00"
    client.lastParams("limit") shouldBe "15"
    client.lastParams("search_vector") shouldBe "plfts(english).Footwear hiking waterproof black"
  }

  test("search uses price=gt.0 when budget is None") {
    val client = new CapturingRestClient
    val provider = new SupabaseProductProvider(client)

    val filters = ExtractedFilters(
      category = None,
      budget = None,
      keywords = List("running"),
      attributes = Map.empty
    )

    provider.search(filters)

    client.lastParams("price") shouldBe "gt.0"
    client.lastParams("limit") shouldBe "30"
    client.lastParams("search_vector") shouldBe "plfts(english).running"
  }

  test("search omits search_vector parameter when search text is empty") {
    val client = new CapturingRestClient
    val provider = new SupabaseProductProvider(client)

    val filters = ExtractedFilters(
      category = None,
      budget = Some(BigDecimal("50.00")),
      keywords = Nil,
      attributes = Map.empty
    )

    provider.search(filters)

    client.lastParams.contains("search_vector") shouldBe false
    client.lastParams("price") shouldBe "lte.50.00"
  }

  test("search correctly parses json into Product sequence") {
    val client = new CapturingRestClient
    client.responseToReturn =
      """[
        |  {
        |    "id": "p1",
        |    "name": "Hiking Shoe",
        |    "brand": "OutdoorBrand",
        |    "category": "Shoes",
        |    "price": 89.99,
        |    "original_price": 109.99,
        |    "rating": "4.2",
        |    "description": "Sturdy boot",
        |    "image_url": "http://img.jpg",
        |    "product_url": "http://prod.com",
        |    "product_specifications": null
        |  }
        |]""".stripMargin

    val provider = new SupabaseProductProvider(client)
    val products = provider.search(ExtractedFilters(None, None, List("boot"), Map.empty))

    products.length shouldBe 1
    val p = products.head
    p.id shouldBe "p1"
    p.name shouldBe "Hiking Shoe"
    p.brand shouldBe Some("OutdoorBrand")
    p.category shouldBe "Shoes"
    p.price shouldBe BigDecimal("89.99")
    p.originalPrice shouldBe Some(BigDecimal("109.99"))
  }
}
