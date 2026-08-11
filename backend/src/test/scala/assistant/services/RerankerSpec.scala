package assistant.services

import assistant.domain.{ExtractedFilters, Product}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class RerankerSpec extends AnyFunSuite with Matchers {

  private def makeProduct(id: String, name: String, category: String, price: Double, desc: String = ""): Product =
    Product(
      id = id,
      name = name,
      brand = None,
      category = category,
      price = BigDecimal(price),
      originalPrice = None,
      rating = None,
      description = if (desc.isEmpty) None else Some(desc),
      imageUrl = None,
      productUrl = None,
      productSpecifications = None
    )

  test("rerank ranks products with more keyword hits higher") {
    val p1 = makeProduct("1", "Basic Leather Shoe", "Footwear", 50.0, "Casual shoe")
    val p2 = makeProduct("2", "Waterproof Leather Hiking Boot", "Footwear", 50.0, "Gore-Tex waterproof boot")
    val p3 = makeProduct("3", "Canvas Sneaker", "Footwear", 50.0, "Lightweight sneaker")

    val filters = ExtractedFilters(
      category = Some("Footwear"),
      budget = None,
      keywords = List("waterproof", "leather", "hiking"),
      attributes = Map.empty
    )

    val ranked = Reranker.rerank(Seq(p1, p2, p3), filters, limit = 5)

    ranked.head.id shouldBe "2" // matches waterproof, leather, hiking
    ranked(1).id shouldBe "1"    // matches leather
    ranked(2).id shouldBe "3"    // 0 hits
  }

  test("rerank penalizes over-budget items relative to under-budget items") {
    val underBudget = makeProduct("under", "Waterproof Boots", "Footwear", 90.0, "Waterproof hiking boots")
    val overBudget = makeProduct("over", "Waterproof Boots Deluxe", "Footwear", 200.0, "Waterproof hiking boots")

    val filters = ExtractedFilters(
      category = Some("Footwear"),
      budget = Some(BigDecimal("100.0")),
      keywords = List("waterproof"),
      attributes = Map.empty
    )

    val ranked = Reranker.rerank(Seq(overBudget, underBudget), filters, limit = 5)

    ranked.head.id shouldBe "under"
  }

  test("rerank respects limit parameter") {
    val products = (1 to 10).map(i => makeProduct(s"p$i", s"Product $i", "Cat", 10.0 * i))
    val filters = ExtractedFilters(None, None, Nil, Map.empty)

    val ranked = Reranker.rerank(products, filters, limit = 3)

    ranked.length shouldBe 3
  }

  test("rerank handles empty candidates list") {
    val filters = ExtractedFilters(Some("Shoes"), Some(BigDecimal("50")), List("running"), Map.empty)
    Reranker.rerank(Nil, filters) shouldBe Seq.empty
  }

  test("rerank favors cheaper under-budget item when keyword scores tie") {
    val cheap = makeProduct("cheap", "Running Shoes Basic", "Footwear", 150.0, "running shoes")
    val pricey = makeProduct("pricey", "Running Shoes Premium", "Footwear", 499.0, "running shoes")

    val filters = ExtractedFilters(
      category = Some("Footwear"),
      budget = Some(BigDecimal("500.0")),
      keywords = List("running"),
      attributes = Map.empty
    )

    val ranked = Reranker.rerank(Seq(pricey, cheap), filters, limit = 5)

    ranked.head.id shouldBe "cheap"
  }

  test("rerank uses rating as tie-break when scores tie") {
    val lowRated = makeProduct("low", "Trail Shoes A", "Footwear", 100.0, "trail shoes")
      .copy(rating = Some("3.5"))
    val highRated = makeProduct("high", "Trail Shoes B", "Footwear", 100.0, "trail shoes")
      .copy(rating = Some("4.3"))

    val filters = ExtractedFilters(
      category = Some("Footwear"),
      budget = Some(BigDecimal("200.0")),
      keywords = List("trail"),
      attributes = Map.empty
    )

    val ranked = Reranker.rerank(Seq(lowRated, highRated), filters, limit = 5)

    ranked.head.id shouldBe "high"
  }

  test("rerank suppresses duplicate names keeping highest-scored copy") {
    val original = makeProduct("orig", "Leather Wallet", "Accessories", 100.0, "leather wallet brown")
      .copy(rating = Some("4.0"))
    val duplicate = makeProduct("dup", "  leather wallet  ", "Accessories", 500.0, "duplicate listing")
      .copy(rating = Some("1.0"))
    val other = makeProduct("other", "Canvas Belt", "Accessories", 80.0, "canvas belt")

    val filters = ExtractedFilters(
      category = Some("Accessories"),
      budget = Some(BigDecimal("200.0")),
      keywords = List("leather"),
      attributes = Map.empty
    )

    val ranked = Reranker.rerank(Seq(duplicate, other, original), filters, limit = 5)

    ranked.map(_.id) should contain theSameElementsAs Seq("orig", "other")
    ranked.head.id shouldBe "orig"
  }
}
