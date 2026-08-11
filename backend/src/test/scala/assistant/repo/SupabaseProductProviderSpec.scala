package assistant.repo

import assistant.domain.ExtractedFilters
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import upickle.default._

import scala.collection.mutable.ListBuffer

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

  /** Records every call; each call dequeues the next queued response
    * (defaults to `[]` when the queue runs dry).
    */
  class CapturingRestClient extends SupabaseRestClient(dummyConfig) {
    val calls: ListBuffer[Map[String, String]] = ListBuffer.empty
    private val responses: scala.collection.mutable.Queue[String] = scala.collection.mutable.Queue.empty

    def enqueueResponse(json: String): Unit = responses.enqueue(json)

    override def get(table: String, params: Map[String, String]): String = {
      calls += params
      if (responses.nonEmpty) responses.dequeue() else "[]"
    }
  }

  private val OneProductJson =
    """[
      |  {
      |    "id": "p1",
      |    "name": "Hiking Shoe",
      |    "brand": "OutdoorBrand",
      |    "category": "Footwear",
      |    "price": 89.99,
      |    "original_price": 109.99,
      |    "rating": "4.2",
      |    "description": "Sturdy boot",
      |    "image_url": "http://img.jpg",
      |    "product_url": "http://prod.com",
      |    "product_specifications": null
      |  }
      |]""".stripMargin

  test("rung 1: strict plfts + price=lte, short-circuits when non-empty") {
    val client = new CapturingRestClient
    client.enqueueResponse(OneProductJson)
    val provider = new SupabaseProductProvider(client)

    val filters = ExtractedFilters(
      category = Some("Footwear"),
      budget = Some(BigDecimal("120.00")),
      keywords = List("hiking", "waterproof"),
      attributes = Map("color" -> "black")
    )

    val products = provider.search(filters, limit = 15)

    products.length shouldBe 1
    client.calls.length shouldBe 1
    val params = client.calls.head
    params("category") shouldBe "not.is.null"
    params("price") shouldBe "lte.120.00"
    params("limit") shouldBe "15"
    params("search_vector") shouldBe "plfts(english).Footwear hiking waterproof black"
  }

  test("rung 1: price=gt.0 when budget is None") {
    val client = new CapturingRestClient
    client.enqueueResponse(OneProductJson)
    val provider = new SupabaseProductProvider(client)

    val filters = ExtractedFilters(
      category = None,
      budget = None,
      keywords = List("running"),
      attributes = Map.empty
    )

    provider.search(filters)

    client.calls.head("price") shouldBe "gt.0"
    client.calls.head("limit") shouldBe "30"
    client.calls.head("search_vector") shouldBe "plfts(english).running"
  }

  test("rung 1: omits search_vector when search text is empty") {
    val client = new CapturingRestClient
    client.enqueueResponse(OneProductJson)
    val provider = new SupabaseProductProvider(client)

    val filters = ExtractedFilters(
      category = None,
      budget = Some(BigDecimal("50.00")),
      keywords = Nil,
      attributes = Map.empty
    )

    provider.search(filters)

    client.calls.head.contains("search_vector") shouldBe false
    client.calls.head("price") shouldBe "lte.50.00"
  }

  test("rung 2: falls back to category=eq + price when rung 1 is empty") {
    val client = new CapturingRestClient
    client.enqueueResponse("[]") // rung 1 empty
    client.enqueueResponse(OneProductJson) // rung 2 hits
    val provider = new SupabaseProductProvider(client)

    val filters = ExtractedFilters(
      category = Some("Kitchen & Dining"),
      budget = Some(BigDecimal("2000")),
      keywords = List("kitchen", "knife", "set"),
      attributes = Map.empty
    )

    val products = provider.search(filters)

    products.length shouldBe 1
    client.calls.length shouldBe 2
    client.calls(1)("category") shouldBe "eq.Kitchen & Dining"
    client.calls(1)("price") shouldBe "lte.2000"
    client.calls(1).contains("search_vector") shouldBe false
  }

  test("rung 3: falls back to websearch on the salient term when rungs 1–2 are empty") {
    val client = new CapturingRestClient
    client.enqueueResponse("[]") // rung 1
    client.enqueueResponse(
      """[
        |  {
        |    "id": "p-wb",
        |    "name": "Waterproof Hiking Boot",
        |    "brand": "TrailCo",
        |    "category": "Footwear",
        |    "price": 2499,
        |    "original_price": null,
        |    "rating": null,
        |    "description": "Waterproof boot for hiking trails.",
        |    "image_url": null,
        |    "product_url": null,
        |    "product_specifications": null
        |  }
        |]""".stripMargin
    ) // rung 3 hits (rung 2 skipped: no category)
    val provider = new SupabaseProductProvider(client)

    val filters = ExtractedFilters(
      category = None,
      budget = None,
      keywords = List("boots", "hiking", "waterproof"),
      attributes = Map.empty
    )

    val products = provider.search(filters)

    products.length shouldBe 1
    client.calls.length shouldBe 2 // rung 1 + rung 3 (rung 2 skipped, no category)
    client.calls(1)("search_vector") shouldBe "wfts(english).waterproof" // longest term wins
  }

  test("returns empty when all rungs exhaust") {
    val client = new CapturingRestClient
    // all responses default to "[]"
    val provider = new SupabaseProductProvider(client)

    val filters = ExtractedFilters(
      category = Some("Footwear"),
      budget = Some(BigDecimal("5000")),
      keywords = List("waterproof", "hiking"),
      attributes = Map.empty
    )

    val products = provider.search(filters)

    products shouldBe empty
    client.calls.length shouldBe 3 // all three rungs attempted
  }

  test("rung 3 quality gate drops results with no keyword overlap in visible text") {
    // An electrical switch whose specs say {"Waterproof": "No"} matches FTS
    // for "waterproof" but shares no vocabulary with "waterproof hiking".
    val JunkJson =
      """[
        |  {
        |    "id": "junk1",
        |    "name": "Avita 15 One Way Electrical Switch",
        |    "brand": "Avita",
        |    "category": "Home Improvement",
        |    "price": 57,
        |    "original_price": null,
        |    "rating": null,
        |    "description": "Buy Avita switch online.",
        |    "image_url": null,
        |    "product_url": null,
        |    "product_specifications": "[{\"key\": \"Waterproof\", \"value\": \"No\"}]"
        |  }
        |]""".stripMargin
    val client = new CapturingRestClient
    client.enqueueResponse("[]") // rung 1
    client.enqueueResponse(JunkJson) // rung 3 (rung 2 skipped: no category)
    val provider = new SupabaseProductProvider(client)

    val filters = ExtractedFilters(
      category = None,
      budget = None,
      keywords = List("waterproof", "hiking"),
      attributes = Map.empty
    )

    provider.search(filters) shouldBe empty
  }

  test("rung 3 quality gate keeps results with keyword overlap in visible text") {
    val MatchJson =
      """[
        |  {
        |    "id": "ok1",
        |    "name": "Wildcraft Waterproof Hiking Backpack",
        |    "brand": "Wildcraft",
        |    "category": "Bags, Wallets & Belts",
        |    "price": 1499,
        |    "original_price": null,
        |    "rating": null,
        |    "description": "Waterproof hiking pack.",
        |    "image_url": null,
        |    "product_url": null,
        |    "product_specifications": null
        |  }
        |]""".stripMargin
    val client = new CapturingRestClient
    client.enqueueResponse("[]") // rung 1
    client.enqueueResponse(MatchJson) // rung 3
    val provider = new SupabaseProductProvider(client)

    val filters = ExtractedFilters(
      category = None,
      budget = None,
      keywords = List("waterproof", "hiking"),
      attributes = Map.empty
    )

    val products = provider.search(filters)
    products.length shouldBe 1
    products.head.id shouldBe "ok1"
  }

  // --- Gender-aware ladder tests ---

  test("rung 1b: retries with gender + salient term when rung 1 is empty") {
    val client = new CapturingRestClient
    client.enqueueResponse("[]") // rung 1 (full term set incl. gender) empty
    client.enqueueResponse(OneProductJson) // rung 1b hits
    val provider = new SupabaseProductProvider(client)

    val filters = ExtractedFilters(
      category = Some("Clothing"),
      budget = Some(BigDecimal("400")),
      keywords = List("shirt", "plain", "black"),
      attributes = Map("color" -> "black", "gender" -> "men")
    )

    val products = provider.search(filters)

    products.length shouldBe 1
    client.calls.length shouldBe 2
    client.calls(0)("search_vector") shouldBe "plfts(english).Clothing shirt plain black black men"
    client.calls(1)("search_vector") shouldBe "plfts(english).men shirt" // gender + longest salient term
    client.calls(1)("price") shouldBe "lte.400"
  }

  test("rung 2: adds gender FTS and negated opposite term when gender is known") {
    val client = new CapturingRestClient
    client.enqueueResponse("[]") // rung 1
    client.enqueueResponse("[]") // rung 1b
    client.enqueueResponse(OneProductJson) // rung 2 hits
    val provider = new SupabaseProductProvider(client)

    val filters = ExtractedFilters(
      category = Some("Clothing"),
      budget = Some(BigDecimal("400")),
      keywords = List("shirt"),
      attributes = Map("gender" -> "men")
    )

    val products = provider.search(filters)

    products.length shouldBe 1
    client.calls.length shouldBe 3
    client.calls(2)("category") shouldBe "eq.Clothing"
    client.calls(2)("search_vector") shouldBe "fts(english).men & !women"
  }

  test("rung 2 stays keyword-free and gender-free when gender is unknown") {
    val client = new CapturingRestClient
    client.enqueueResponse("[]") // rung 1
    client.enqueueResponse(OneProductJson) // rung 2 hits
    val provider = new SupabaseProductProvider(client)

    val filters = ExtractedFilters(
      category = Some("Mobiles & Accessories"),
      budget = None,
      keywords = List("samsung"),
      attributes = Map.empty
    )

    provider.search(filters)

    client.calls.length shouldBe 2 // no rung 1b without gender
    client.calls(1).contains("search_vector") shouldBe false
  }

  test("rung 3 quality gate requires a word-boundary gender hit when gender is known") {
    val WomensJson =
      """[
        |  {
        |    "id": "w1",
        |    "name": "Species Women's Floral Print Casual Shirt",
        |    "brand": null,
        |    "category": "Clothing",
        |    "price": 359,
        |    "original_price": null,
        |    "rating": null,
        |    "description": "Women casual shirt.",
        |    "image_url": null,
        |    "product_url": null,
        |    "product_specifications": null
        |  }
        |]""".stripMargin
    val client = new CapturingRestClient
    client.enqueueResponse("[]") // rung 1
    client.enqueueResponse("[]") // rung 1b
    client.enqueueResponse("[]") // rung 2
    client.enqueueResponse(WomensJson) // rung 3 raw hit, must be gated out
    val provider = new SupabaseProductProvider(client)

    val filters = ExtractedFilters(
      category = Some("Clothing"),
      budget = Some(BigDecimal("400")),
      keywords = List("shirt"),
      attributes = Map("gender" -> "men")
    )

    provider.search(filters) shouldBe empty // "women" must not satisfy the "men" requirement
  }

  test("search correctly parses json into Product sequence") {
    val client = new CapturingRestClient
    client.enqueueResponse(
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
    )

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
