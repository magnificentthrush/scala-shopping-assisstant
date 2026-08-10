package assistant.domain

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import upickle.default._

class ProductSpec extends AnyFunSuite with Matchers {

  test("Product round-trips through JSON") {
    val product = Product(
      id = "abc123",
      name = "TrailGuard Hiking Shoe",
      brand = Some("Acme"),
      category = "hiking shoes",
      price = BigDecimal("99.99"),
      originalPrice = Some(BigDecimal("129.99")),
      rating = Some("4.3"),
      description = Some("Waterproof hiking shoe"),
      imageUrl = Some("https://example.com/shoe.jpg"),
      productUrl = Some("https://example.com/product/abc123"),
      productSpecifications = Some("Size 9, Waterproof")
    )
    val json = write(product)
    val roundTripped = read[Product](json)
    roundTripped shouldBe product
  }

  test("Product round-trips through JSON with null optional fields") {
    val product = Product(
      id = "xyz789",
      name = "Basic Shoe",
      brand = None,
      category = "shoes",
      price = BigDecimal("49.99"),
      originalPrice = None,
      rating = None,
      description = None,
      imageUrl = None,
      productUrl = None,
      productSpecifications = None
    )
    val json = write(product)
    val roundTripped = read[Product](json)
    roundTripped shouldBe product
  }

  test("ExtractedFilters round-trips through JSON") {
    val filters = ExtractedFilters(
      category = Some("hiking shoes"),
      budget = Some(BigDecimal("120")),
      keywords = List("waterproof", "hiking"),
      attributes = Map("waterproof" -> "true")
    )
    val json = write(filters)
    val roundTripped = read[ExtractedFilters](json)
    roundTripped shouldBe filters
  }

  test("ExtractedFilters round-trips through JSON with empty/none values") {
    val filters = ExtractedFilters(
      category = None,
      budget = None,
      keywords = Nil,
      attributes = Map.empty
    )
    val json = write(filters)
    val roundTripped = read[ExtractedFilters](json)
    roundTripped shouldBe filters
  }
}