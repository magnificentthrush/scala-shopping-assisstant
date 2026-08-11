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
}
