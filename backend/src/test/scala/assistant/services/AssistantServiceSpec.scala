package assistant.services

import assistant.config.AppConfig
import assistant.domain._
import assistant.repo._
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class AssistantServiceSpec extends AnyFunSuite with Matchers {

  private val dummyConfig = AppConfig(
    jwtSecret = "secret",
    jwtExpiresInHours = 1L,
    supabaseUrl = "http://localhost",
    supabaseKey = "key",
    resendApiKey = "",
    emailFrom = "",
    frontendUrl = "",
    gemmaApiKey = ""
  )

  private class TestLLMClient(var jsonToReturn: String, var shouldThrow: Boolean = false) extends LLMClient {
    var generateCalled: Boolean = false
    override def generate(prompt: String): LLMResponse = {
      generateCalled = true
      if (shouldThrow) throw new RuntimeException("LLM API Error")
      LLMResponse(jsonToReturn, "test-model")
    }
  }

  private class TestProductProvider(var productsToReturn: Seq[Product], var shouldThrow: Boolean = false)
      extends ProductProvider {
    var searchCalled: Boolean = false
    override def search(filters: ExtractedFilters, limit: Int): Seq[Product] = {
      searchCalled = true
      if (shouldThrow) throw new RuntimeException("DB Search Error")
      productsToReturn
    }
  }

  private class TestRestClient(var rpcShouldThrow: Boolean = false) extends SupabaseRestClient(dummyConfig) {
    // Records every PATCH on `conversations` as (params, body) so title tests
    // can assert the auto-title call without a live DB.
    val conversationPatches = scala.collection.mutable.ListBuffer[(Map[String, String], String)]()

    override def get(table: String, params: Map[String, String]): String = {
      if (table == "conversation_state") {
        """[{"conversation_id":"c1","filters":{},"updated_at":"2026-08-11T10:00:00Z"}]"""
      } else if (table == "messages") {
        """[]"""
      } else {
        "[]"
      }
    }

    override def patch(table: String, params: Map[String, String], jsonBody: String): String = {
      if (table == "conversations") {
        conversationPatches += ((params, jsonBody))
        // Echo a row back only when the title=is.null guard "matched" — the
        // test controls this via the params filter just like real PostgREST.
        if (params.get("title").contains("is.null")) {
          """[{"id":"c1","user_id":"u1","title":"t","created_at":"2026-08-11T10:00:00Z","updated_at":"2026-08-11T10:00:00Z","last_message_at":"2026-08-11T10:00:00Z"}]"""
        } else "[]"
      } else "[]"
    }

    override def rpc(name: String, jsonBody: String): String = {
      if (rpcShouldThrow) throw new RuntimeException("RPC Error")
      """[{"id":"m-asst-1","conversation_id":"c1","sequence_number":2,"role":"assistant","content":"Reply text","filters_snapshot":null,"created_at":"2026-08-11T10:00:05Z"}]"""
    }
  }

  private def makeProduct(id: String, name: String, category: String, price: Double): Product =
    Product(
      id = id,
      name = name,
      brand = None,
      category = category,
      price = BigDecimal(price),
      originalPrice = None,
      rating = None,
      description = None,
      imageUrl = None,
      productUrl = None,
      productSpecifications = None
    )

  test("respond handles mode recommend with search results") {
    val llmJson =
      """{
        |  "mode": "recommend",
        |  "filters": { "category": "Footwear", "budget": 100.0, "keywords": ["hiking"], "attributes": { "gender": "men" } },
        |  "assistantResponse": "Here are boots under $100.",
        |  "followUpQuestion": null
        |}""".stripMargin

    val llmClient = new TestLLMClient(llmJson)
    val products = Seq(makeProduct("p1", "Men Hiking Boot", "Footwear", 89.99))
    val provider = new TestProductProvider(products)
    val client = new TestRestClient()
    val stateRepo = new ConversationStateRepo(client)
    val msgRepo = new MessageRepo(client)

    val service = new AssistantService(llmClient, provider, stateRepo, msgRepo, new ConversationRepo(client))
    val result = service.respond("c1", "I need hiking boots under $100")

    result.isRight shouldBe true
    val turn = result.toOption.get
    turn.mode shouldBe "recommend"
    turn.reply should startWith("Here are boots under $100.")
    turn.reply should include("under ₹100")
    turn.products.length shouldBe 1
    turn.products.head.id shouldBe "p1"
    provider.searchCalled shouldBe true
  }

  test("respond handles mode recommend with zero search results") {
    val llmJson =
      """{
        |  "mode": "recommend",
        |  "filters": { "category": "Footwear", "keywords": ["nonexistent"], "attributes": { "gender": "men" } },
        |  "assistantResponse": "No boots found.",
        |  "followUpQuestion": null
        |}""".stripMargin

    val llmClient = new TestLLMClient(llmJson)
    val provider = new TestProductProvider(Seq.empty)
    val client = new TestRestClient()
    val stateRepo = new ConversationStateRepo(client)
    val msgRepo = new MessageRepo(client)

    val service = new AssistantService(llmClient, provider, stateRepo, msgRepo, new ConversationRepo(client))
    val result = service.respond("c1", "I need nonexistent boots")

    result.isRight shouldBe true
    val turn = result.toOption.get
    turn.mode shouldBe "recommend"
    turn.products shouldBe Seq.empty
    turn.reply should include("couldn't find anything")
    turn.followUpQuestion should not be empty
    provider.searchCalled shouldBe true
  }

  test("respond keeps the LLM reply when search returns results") {
    val llmJson =
      """{
        |  "mode": "recommend",
        |  "filters": { "category": "Watches", "keywords": ["watch"], "attributes": { "gender": "men" } },
        |  "assistantResponse": "Here are watches.",
        |  "followUpQuestion": null
        |}""".stripMargin

    val llmClient = new TestLLMClient(llmJson)
    val provider = new TestProductProvider(Seq(makeProduct("p1", "Men's Watch", "Watches", 999.0)))
    val client = new TestRestClient()
    val stateRepo = new ConversationStateRepo(client)
    val msgRepo = new MessageRepo(client)

    val service = new AssistantService(llmClient, provider, stateRepo, msgRepo, new ConversationRepo(client))
    val result = service.respond("c1", "show me watches")

    result.isRight shouldBe true
    val turn = result.toOption.get
    turn.reply should startWith("Here are watches.")
    turn.reply should include("(Filters: Watches")
    turn.followUpQuestion shouldBe None
    turn.products.length shouldBe 1
  }

  test("respond appends INR-formatted budget in the filter summary") {
    val llmJson =
      """{
        |  "mode": "recommend",
        |  "filters": { "category": "Watches", "budget": 2000.0, "keywords": ["men's watch"], "attributes": {} },
        |  "assistantResponse": "Here are watches under your budget.",
        |  "followUpQuestion": null
        |}""".stripMargin

    val llmClient = new TestLLMClient(llmJson)
    val provider = new TestProductProvider(Seq(makeProduct("p1", "Men's Watch", "Watches", 1599.0)))
    val client = new TestRestClient()
    val stateRepo = new ConversationStateRepo(client)
    val msgRepo = new MessageRepo(client)

    val service = new AssistantService(llmClient, provider, stateRepo, msgRepo, new ConversationRepo(client))
    val result = service.respond("c1", "men's watch under 2000")

    result.isRight shouldBe true
    val turn = result.toOption.get
    turn.reply should include("under ₹2,000")
    turn.reply should include("Watches")
  }

  test("respond handles mode clarify without executing search") {
    val llmJson =
      """{
        |  "mode": "clarify",
        |  "filters": { "category": null, "budget": null, "keywords": [], "attributes": {} },
        |  "assistantResponse": "What category of product are you looking for?",
        |  "followUpQuestion": "What is your budget?"
        |}""".stripMargin

    val llmClient = new TestLLMClient(llmJson)
    val provider = new TestProductProvider(Seq.empty)
    val client = new TestRestClient()
    val stateRepo = new ConversationStateRepo(client)
    val msgRepo = new MessageRepo(client)

    val service = new AssistantService(llmClient, provider, stateRepo, msgRepo, new ConversationRepo(client))
    val result = service.respond("c1", "Hello")

    result.isRight shouldBe true
    val turn = result.toOption.get
    turn.mode shouldBe "clarify"
    turn.products shouldBe Seq.empty
    turn.followUpQuestion shouldBe Some("What is your budget?")
    provider.searchCalled shouldBe false // Verification that search is skipped on clarify
  }

  test("respond returns ASSISTANT_FAILED (500) when Call #2 LLM fails") {
    val llmClient = new TestLLMClient("", shouldThrow = true)
    val provider = new TestProductProvider(Seq.empty)
    val client = new TestRestClient()
    val stateRepo = new ConversationStateRepo(client)
    val msgRepo = new MessageRepo(client)

    val service = new AssistantService(llmClient, provider, stateRepo, msgRepo, new ConversationRepo(client))
    val result = service.respond("c1", "Find shoes")

    result.isLeft shouldBe true
    val failure = result.left.toOption.get
    failure.status shouldBe 500
    failure.code shouldBe Some("ASSISTANT_FAILED")
  }

  test("respond returns UPSTREAM_UNAVAILABLE (503) when post-Call-#2 DB commit RPC fails") {
    val llmJson =
      """{
        |  "mode": "recommend",
        |  "filters": { "category": "Shoes", "keywords": [], "attributes": {} },
        |  "assistantResponse": "Here are shoes.",
        |  "followUpQuestion": null
        |}""".stripMargin

    val llmClient = new TestLLMClient(llmJson)
    val provider = new TestProductProvider(Seq.empty)
    val client = new TestRestClient(rpcShouldThrow = true)
    val stateRepo = new ConversationStateRepo(client)
    val msgRepo = new MessageRepo(client)

    val service = new AssistantService(llmClient, provider, stateRepo, msgRepo, new ConversationRepo(client))
    val result = service.respond("c1", "Find shoes")

    result.isLeft shouldBe true
    val failure = result.left.toOption.get
    failure.status shouldBe 503
    failure.code shouldBe Some("UPSTREAM_UNAVAILABLE")
  }

  // --- Discovery-first readiness gate tests ---

  test("respond forces clarify and asks men/women when gender is missing for a gender-relevant category") {
    val llmJson =
      """{
        |  "mode": "recommend",
        |  "filters": { "category": "Footwear", "budget": null, "keywords": [], "attributes": {} },
        |  "assistantResponse": "Here are some shoes.",
        |  "followUpQuestion": null
        |}""".stripMargin

    val llmClient = new TestLLMClient(llmJson)
    val provider = new TestProductProvider(Seq(makeProduct("p1", "Sneaker", "Footwear", 999.0)))
    val client = new TestRestClient()
    val service = new AssistantService(
      llmClient, provider, new ConversationStateRepo(client), new MessageRepo(client), new ConversationRepo(client)
    )

    val result = service.respond("c1", "I want shoes")

    result.isRight shouldBe true
    val turn = result.toOption.get
    turn.mode shouldBe "clarify"
    turn.products shouldBe Seq.empty
    turn.followUpQuestion shouldBe Some("Are you shopping for men or women?")
    turn.reply should include("Are you shopping for men or women?")
    provider.searchCalled shouldBe false
  }

  test("respond detects gender from keywords and asks for one more signal instead") {
    val llmJson =
      """{
        |  "mode": "recommend",
        |  "filters": { "category": "Footwear", "budget": null, "keywords": [], "attributes": { "gender": "women" } },
        |  "assistantResponse": "Here are some shoes.",
        |  "followUpQuestion": null
        |}""".stripMargin

    val llmClient = new TestLLMClient(llmJson)
    val provider = new TestProductProvider(Seq(makeProduct("p1", "Heels", "Footwear", 1999.0)))
    val client = new TestRestClient()
    val service = new AssistantService(
      llmClient, provider, new ConversationStateRepo(client), new MessageRepo(client), new ConversationRepo(client)
    )

    val result = service.respond("c1", "for women")

    result.isRight shouldBe true
    val turn = result.toOption.get
    turn.mode shouldBe "clarify"
    turn.products shouldBe Seq.empty
    turn.followUpQuestion shouldBe Some("Do you have a budget, brand, color, or use-case in mind?")
    provider.searchCalled shouldBe false
  }

  test("respond searches when category, gender, and an extra signal are all present") {
    val llmJson =
      """{
        |  "mode": "recommend",
        |  "filters": { "category": "Footwear", "budget": null, "keywords": ["running"], "attributes": { "gender": "men" } },
        |  "assistantResponse": "Here are running shoes for men.",
        |  "followUpQuestion": null
        |}""".stripMargin

    val llmClient = new TestLLMClient(llmJson)
    val provider = new TestProductProvider(Seq(makeProduct("p1", "Men Running Shoe", "Footwear", 1499.0)))
    val client = new TestRestClient()
    val service = new AssistantService(
      llmClient, provider, new ConversationStateRepo(client), new MessageRepo(client), new ConversationRepo(client)
    )

    val result = service.respond("c1", "running shoes for men")

    result.isRight shouldBe true
    val turn = result.toOption.get
    turn.mode shouldBe "recommend"
    turn.products.length shouldBe 1
    provider.searchCalled shouldBe true
  }

  test("respond does not ask about gender for a non-gender-relevant category") {
    val llmJson =
      """{
        |  "mode": "recommend",
        |  "filters": { "category": "Mobiles & Accessories", "budget": null, "keywords": ["samsung"], "attributes": {} },
        |  "assistantResponse": "Here are some Samsung phones.",
        |  "followUpQuestion": null
        |}""".stripMargin

    val llmClient = new TestLLMClient(llmJson)
    val provider = new TestProductProvider(Seq(makeProduct("p1", "Samsung Galaxy", "Mobiles & Accessories", 14999.0)))
    val client = new TestRestClient()
    val service = new AssistantService(
      llmClient, provider, new ConversationStateRepo(client), new MessageRepo(client), new ConversationRepo(client)
    )

    val result = service.respond("c1", "samsung phone")

    result.isRight shouldBe true
    val turn = result.toOption.get
    turn.mode shouldBe "recommend"
    turn.products.length shouldBe 1
    turn.reply should not include "men or women"
    provider.searchCalled shouldBe true
  }

  test("respond never returns opposite-gender products when gender filter is set") {
    val llmJson =
      """{
        |  "mode": "recommend",
        |  "filters": { "category": "Clothing", "budget": 400.0, "keywords": ["shirt", "plain", "black"], "attributes": { "color": "black", "gender": "men" } },
        |  "assistantResponse": "Here are plain black shirts for men.",
        |  "followUpQuestion": null
        |}""".stripMargin

    val llmClient = new TestLLMClient(llmJson)
    // The provider hands back a mixed pool (as rung 2 did in production); the
    // reranker's hard gender gate must keep only the men's shirt.
    val provider = new TestProductProvider(Seq(
      makeProduct("w1", "Species Women's Floral Print Casual Shirt", "Clothing", 359.0),
      makeProduct("w2", "Kiosha Women's Solid Casual Shirt", "Clothing", 297.0),
      makeProduct("m1", "Roadster Men Black Plain Casual Shirt", "Clothing", 399.0)
    ))
    val client = new TestRestClient()
    val service = new AssistantService(
      llmClient, provider, new ConversationStateRepo(client), new MessageRepo(client), new ConversationRepo(client)
    )

    val result = service.respond("c1", "no design, plain black shirt")

    result.isRight shouldBe true
    val turn = result.toOption.get
    turn.products.map(_.id) shouldBe Seq("m1")
    turn.products.exists(_.name.toLowerCase.contains("women")) shouldBe false
  }

  // --- Option B auto-title tests ---

  test("deriveTitle uses category, appends INR budget") {
    val filters = ExtractedFilters(
      category = Some("Hiking shoes"),
      budget = Some(BigDecimal(9960)),
      keywords = List("waterproof"),
      attributes = Map.empty
    )
    val client = new TestRestClient()
    val service = new AssistantService(
      new TestLLMClient("{}"), new TestProductProvider(Seq.empty),
      new ConversationStateRepo(client), new MessageRepo(client), new ConversationRepo(client)
    )
    service.deriveTitle(filters) shouldBe Some("Hiking shoes · under ₹9,960")
  }

  test("deriveTitle falls back to first keyword when no category, None when filters empty") {
    val client = new TestRestClient()
    val service = new AssistantService(
      new TestLLMClient("{}"), new TestProductProvider(Seq.empty),
      new ConversationStateRepo(client), new MessageRepo(client), new ConversationRepo(client)
    )
    service.deriveTitle(ExtractedFilters(None, None, List("waterproof boots"), Map.empty)) shouldBe
      Some("waterproof boots")
    service.deriveTitle(ExtractedFilters(None, None, List.empty, Map.empty)) shouldBe None
  }

  test("respond auto-titles an untitled conversation from filters (title=is.null guard)") {
    val llmJson =
      """{
        |  "mode": "recommend",
        |  "filters": { "category": "Hiking shoes", "budget": 9960.0, "keywords": ["waterproof"], "attributes": {} },
        |  "assistantResponse": "Here are some options.",
        |  "followUpQuestion": null
        |}""".stripMargin

    val llmClient = new TestLLMClient(llmJson)
    val provider = new TestProductProvider(Seq(makeProduct("p1", "Boot", "Hiking shoes", 9000.0)))
    val client = new TestRestClient()
    val service = new AssistantService(
      llmClient, provider, new ConversationStateRepo(client), new MessageRepo(client), new ConversationRepo(client)
    )

    val result = service.respond("c1", "I need waterproof hiking shoes under $120")
    result.isRight shouldBe true

    // Exactly one PATCH to conversations, carrying the title=is.null guard and
    // the derived title in the body.
    client.conversationPatches.length shouldBe 1
    val (params, body) = client.conversationPatches.head
    params.get("id") shouldBe Some("eq.c1")
    params.get("title") shouldBe Some("is.null")
    body should include("Hiking shoes")
    body should include("₹9,960")
  }

  test("respond skips auto-title when filters are empty (clarify/info turn)") {
    val llmJson =
      """{
        |  "mode": "clarify",
        |  "filters": { "category": null, "budget": null, "keywords": [], "attributes": {} },
        |  "assistantResponse": "What are you looking for?",
        |  "followUpQuestion": "Budget?"
        |}""".stripMargin

    val llmClient = new TestLLMClient(llmJson)
    val provider = new TestProductProvider(Seq.empty)
    val client = new TestRestClient()
    val service = new AssistantService(
      llmClient, provider, new ConversationStateRepo(client), new MessageRepo(client), new ConversationRepo(client)
    )

    service.respond("c1", "Hello")
    client.conversationPatches shouldBe empty
  }

  test("respond never fails the turn when auto-title PATCH throws") {
    val llmJson =
      """{
        |  "mode": "recommend",
        |  "filters": { "category": "Hiking shoes", "keywords": ["hiking"], "attributes": {} },
        |  "assistantResponse": "Here are shoes.",
        |  "followUpQuestion": null
        |}""".stripMargin

    val llmClient = new TestLLMClient(llmJson)
    val provider = new TestProductProvider(Seq(makeProduct("p1", "Boot", "Hiking shoes", 100.0)))
    // A client whose conversations PATCH blows up.
    val client = new TestRestClient() {
      override def patch(table: String, params: Map[String, String], jsonBody: String): String =
        if (table == "conversations") throw new RuntimeException("title patch exploded")
        else super.patch(table, params, jsonBody)
    }
    val service = new AssistantService(
      llmClient, provider, new ConversationStateRepo(client), new MessageRepo(client), new ConversationRepo(client)
    )

    // The turn must still succeed despite the title failure.
    val result = service.respond("c1", "hiking shoes")
    result.isRight shouldBe true
    result.toOption.get.products.length shouldBe 1
  }
}
