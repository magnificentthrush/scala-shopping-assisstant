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

  test("rerank drops candidates below the keyword-relevance gate and keeps the qualifying one") {
    val p1 = makeProduct("1", "Basic Leather Shoe", "Footwear", 50.0, "Casual shoe") // 1 hit ("leather") < minHits
    val p2 = makeProduct("2", "Waterproof Leather Hiking Boot", "Footwear", 50.0, "Gore-Tex waterproof boot") // 3 hits
    val p3 = makeProduct("3", "Canvas Sneaker", "Footwear", 50.0, "Lightweight sneaker") // 0 hits

    val filters = ExtractedFilters(
      category = Some("Footwear"),
      budget = None,
      keywords = List("waterproof", "leather", "hiking"),
      attributes = Map.empty
    )

    val result = Reranker.rerank(Seq(p1, p2, p3), filters, limit = 5)

    result.isExactMatch shouldBe true
    result.products.map(_.id) shouldBe Seq("2") // only the fully-matching boot clears the gate
  }

  test("rerank ranks multiple gate-qualifying candidates by weighted keyword hits") {
    val higherHits = makeProduct("high", "Waterproof Leather Hiking Boot", "Footwear", 50.0, "waterproof leather hiking boot") // 3 hits
    val lowerHits = makeProduct("low", "Leather Hiking Shoe", "Footwear", 50.0, "leather hiking shoe") // 2 hits
    val irrelevant = makeProduct("irrelevant", "Canvas Sneaker", "Footwear", 50.0, "Lightweight sneaker") // 0 hits

    val filters = ExtractedFilters(
      category = Some("Footwear"),
      budget = None,
      keywords = List("waterproof", "leather", "hiking"),
      attributes = Map.empty
    )

    val result = Reranker.rerank(Seq(lowerHits, irrelevant, higherHits), filters, limit = 5)

    result.isExactMatch shouldBe true
    result.products.map(_.id) shouldBe Seq("high", "low") // irrelevant candidate dropped entirely
  }

  test("rerank falls back to the full pool with isExactMatch=false when nothing clears the relevance gate") {
    // Both candidates only hit the generic "shoes" term, never the specific "running" intent —
    // this is the "football shoes surface for a running shoes query" scenario.
    val footballShoe = makeProduct("football", "Port Fusion Football Shoes", "Footwear", 599.0, "Football shoes")
      .copy(rating = Some("4.0"))
    val formalShoe = makeProduct("formal", "Provogue Corporate Casuals", "Footwear", 2099.0, "Formal shoes")
      .copy(rating = Some("3.0"))

    val filters = ExtractedFilters(
      category = Some("Footwear"),
      budget = None,
      keywords = List("running", "shoes"),
      attributes = Map.empty
    )

    val result = Reranker.rerank(Seq(footballShoe, formalShoe), filters, limit = 5)

    result.isExactMatch shouldBe false
    result.products should not be empty // still returns a best-effort answer rather than nothing
    result.products.map(_.id) should contain theSameElementsAs Seq("football", "formal")
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

    val result = Reranker.rerank(Seq(overBudget, underBudget), filters, limit = 5)

    result.products.head.id shouldBe "under"
  }

  test("rerank respects limit parameter") {
    val products = (1 to 10).map(i => makeProduct(s"p$i", s"Product $i", "Cat", 10.0 * i))
    val filters = ExtractedFilters(None, None, Nil, Map.empty)

    val result = Reranker.rerank(products, filters, limit = 3)

    result.products.length shouldBe 3
  }

  test("rerank handles empty candidates list") {
    val filters = ExtractedFilters(Some("Shoes"), Some(BigDecimal("50")), List("running"), Map.empty)
    Reranker.rerank(Nil, filters).products shouldBe Seq.empty
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

    val result = Reranker.rerank(Seq(pricey, cheap), filters, limit = 5)

    result.products.head.id shouldBe "cheap"
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

    val result = Reranker.rerank(Seq(lowRated, highRated), filters, limit = 5)

    result.products.head.id shouldBe "high"
  }

  // --- Gender gate tests ---

  test("word-boundary matching: 'women' never scores a hit for the term 'men'") {
    val womensShirt = makeProduct("w", "Species Women's Floral Print Casual Shirt", "Clothing", 359.0, "women shirt")
    val mensShirt = makeProduct("m", "Ketch Men's Solid Casual Shirt", "Clothing", 399.0, "men shirt")

    val filters = ExtractedFilters(
      category = Some("Clothing"),
      budget = None,
      keywords = List("men", "shirt"),
      attributes = Map.empty
    )

    val result = Reranker.rerank(Seq(womensShirt, mensShirt), filters, limit = 5)

    result.isExactMatch shouldBe true
    result.products.map(_.id) shouldBe Seq("m") // women's shirt is hard-excluded, not just outscored
  }

  test("gender attribute excludes opposite-gender products and requires a gender hit") {
    val womensTop = makeProduct("w", "Fashion2wear Women Printed Black Top", "Clothing", 399.0, "black top")
    val unlabeled = makeProduct("u", "Plain Black Shirt", "Clothing", 350.0, "plain black shirt") // hits keywords but no gender
    val mensShirt = makeProduct("m", "Roadster Men Black Plain Shirt", "Clothing", 399.0, "men black plain shirt")

    val filters = ExtractedFilters(
      category = Some("Clothing"),
      budget = Some(BigDecimal("400")),
      keywords = List("shirt", "plain", "black"),
      attributes = Map("gender" -> "men")
    )

    val result = Reranker.rerank(Seq(womensTop, unlabeled, mensShirt), filters, limit = 5)

    result.isExactMatch shouldBe true
    result.products.map(_.id) shouldBe Seq("m")
  }

  test("unlabeled candidates fall back as best-effort (isExactMatch=false) when none carry the gender word") {
    val unlabeled = makeProduct("u", "Plain Black Shirt", "Clothing", 350.0, "plain black shirt")

    val filters = ExtractedFilters(
      category = Some("Clothing"),
      budget = None,
      keywords = List("shirt"),
      attributes = Map("gender" -> "men")
    )

    val result = Reranker.rerank(Seq(unlabeled), filters, limit = 5)

    result.isExactMatch shouldBe false
    result.products.map(_.id) shouldBe Seq("u")
  }

  test("returns empty with isExactMatch=false when opposite-gender exclusion empties the pool") {
    val womensShirt = makeProduct("w", "Species Women's Floral Print Casual Shirt", "Clothing", 359.0)

    val filters = ExtractedFilters(
      category = Some("Clothing"),
      budget = None,
      keywords = List("shirt"),
      attributes = Map("gender" -> "men")
    )

    val result = Reranker.rerank(Seq(womensShirt), filters, limit = 5)

    result.isExactMatch shouldBe false
    result.products shouldBe Seq.empty
  }

  test("rerank suppresses duplicate names keeping highest-scored copy") {
    val original = makeProduct("orig", "Leather Wallet", "Accessories", 100.0, "leather wallet brown")
      .copy(rating = Some("4.0"))
    val duplicate = makeProduct("dup", "  leather wallet  ", "Accessories", 500.0, "duplicate listing")
      .copy(rating = Some("1.0"))
    val other = makeProduct("other", "Canvas Belt", "Accessories", 80.0, "canvas belt")

    // No keywords here — this test exercises the dedupe/price/rating logic, not
    // the relevance gate, so "other" (an unrelated product) must stay in the pool.
    val filters = ExtractedFilters(
      category = Some("Accessories"),
      budget = Some(BigDecimal("200.0")),
      keywords = Nil,
      attributes = Map.empty
    )

    val result = Reranker.rerank(Seq(duplicate, other, original), filters, limit = 5)

    result.products.map(_.id) should contain theSameElementsAs Seq("orig", "other")
    result.products.head.id shouldBe "orig"
  }
}
