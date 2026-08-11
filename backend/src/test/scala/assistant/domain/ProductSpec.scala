package assistant.domain

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import upickle.default._

class ProductSpec extends AnyFunSuite with Matchers {

  test("Product serializes to and deserializes from JSON with all fields populated") {
    val product = Product(
      id = "prod-123",
      name = "Trail Runner Boot",
      brand = Some("OutdoorsCo"),
      category = "Footwear",
      price = BigDecimal("119.99"),
      originalPrice = Some(BigDecimal("149.99")),
      rating = Some("4.5"),
      description = Some("Durable waterproof hiking boot"),
      imageUrl = Some("http://example.com/img.jpg"),
      productUrl = Some("http://example.com/prod/123"),
      productSpecifications = Some("Gore-Tex, Vibram Sole")
    )

    val json = write(product)
    val parsed = read[Product](json)

    parsed shouldBe product
    parsed.price shouldBe BigDecimal("119.99")
    parsed.originalPrice shouldBe Some(BigDecimal("149.99"))
  }

  test("Product handles optional fields set to None / null in JSON") {
    val json =
      """{
        |  "id": "prod-456",
        |  "name": "Basic Tee",
        |  "brand": null,
        |  "category": "Apparel",
        |  "price": 19.99,
        |  "originalPrice": null,
        |  "rating": null,
        |  "description": null,
        |  "imageUrl": null,
        |  "productUrl": null,
        |  "productSpecifications": null
        |}""".stripMargin

    val product = read[Product](json)

    product.id shouldBe "prod-456"
    product.name shouldBe "Basic Tee"
    product.brand shouldBe None
    product.category shouldBe "Apparel"
    product.price shouldBe BigDecimal("19.99")
    product.originalPrice shouldBe None
    product.description shouldBe None
  }

  test("ExtractedFilters serializes to and deserializes from JSON") {
    val filters = ExtractedFilters(
      category = Some("Shoes"),
      budget = Some(BigDecimal("100.00")),
      keywords = List("waterproof", "running"),
      attributes = Map("color" -> "black", "size" -> "10")
    )

    val json = write(filters)
    val parsed = read[ExtractedFilters](json)

    parsed shouldBe filters
    parsed.category shouldBe Some("Shoes")
    parsed.budget shouldBe Some(BigDecimal("100.00"))
    parsed.keywords shouldBe List("waterproof", "running")
    parsed.attributes shouldBe Map("color" -> "black", "size" -> "10")
  }

  test("ExtractedFilters handles null budget and empty lists/maps") {
    val json =
      """{
        |  "category": null,
        |  "budget": null,
        |  "keywords": [],
        |  "attributes": {}
        |}""".stripMargin

    val filters = read[ExtractedFilters](json)

    filters.category shouldBe None
    filters.budget shouldBe None
    filters.keywords shouldBe Nil
    filters.attributes shouldBe Map.empty
  }
}
