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

  /** Routes fake LLM responses by inspecting the prompt: Call #3 (RelevanceCheck)
    * prompts carry the numbered candidate list / verdict instructions, while Call #2
    * (AssistantPrompt) prompts carry the ShopPilot system prompt and conversation state.
    * Call counts let tests prove Call #3 did or did not run.
    */
  private class TestLLMClient(
      call2Json: String,
      call2ShouldThrow: Boolean = false,
      call3Json: String = """{"verdict": "match", "matchedIds": [], "suggestion": null}""",
      call3ShouldThrow: Boolean = false
  ) extends LLMClient {
    var call2Count: Int = 0
    var call3Count: Int = 0
    override def generate(prompt: String): LLMResponse = {
      if (prompt.contains("relevance judge") || prompt.contains("Candidate products:")) {
        call3Count += 1
        if (call3ShouldThrow) throw new RuntimeException("Relevance check exploded")
        LLMResponse(call3Json, "test-model")
      } else {
        call2Count += 1
        if (call2ShouldThrow) throw new RuntimeException("LLM API Error")
        LLMResponse(call2Json, "test-model")
      }
    }
  }

  private class TestProductProvider(var productsToReturn: Seq[Product], var shouldThrow: Boolean = false)
      extends ProductProvider {
    var searchCalled: Boolean = false
    var lastSearchFilters: Option[ExtractedFilters] = None
    override def search(filters: ExtractedFilters, limit: Int): Seq[Product] = {
      searchCalled = true
      lastSearchFilters = Some(filters)
      if (shouldThrow) throw new RuntimeException("DB Search Error")
      productsToReturn
    }
  }

  /** Captures the state envelope handed to MessageRepo.insertAssistantMessage so
    * tests can assert the {filters, pending} shape without a live DB.
    */
  private class FakeMessageRepo(client: SupabaseRestClient) extends MessageRepo(client) {
    var lastEnvelope: Option[ujson.Value] = None
    var lastProducts: Option[Seq[Product]] = None
    override def insertAssistantMessage(
        conversationId: String,
        content: String,
        filters: ujson.Value,
        products: Seq[Product]
    ): MessageRow = {
      lastEnvelope = Some(filters)
      lastProducts = Some(products)
      MessageRow(
        id = "m-asst-1",
        conversationId = conversationId,
        sequenceNumber = 2,
        role = "assistant",
        content = content,
        createdAt = "2026-08-11T10:00:05Z"
      )
    }
  }

  private class TestRestClient(var rpcShouldThrow: Boolean = false, stateFiltersJson: String = "{}")
      extends SupabaseRestClient(dummyConfig) {
    // Records every PATCH on `conversations` as (params, body) so title tests
    // can assert the auto-title call without a live DB.
    val conversationPatches = scala.collection.mutable.ListBuffer[(Map[String, String], String)]()

    override def get(table: String, params: Map[String, String]): String = {
      if (table == "conversation_state") {
        s"""[{"conversation_id":"c1","filters":$stateFiltersJson,"updated_at":"2026-08-11T10:00:00Z"}]"""
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
    val llmClient = new TestLLMClient("", call2ShouldThrow = true)
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

  // --- Call #3 relevance re-check + pending-offer flow tests ---

  // All three products clear the reranker's relevance gate for
  // filters {keywords: ["hiking"], attributes: {gender: "men"}} and tie on
  // score, so the price-ascending tie-break fixes the rerank order as
  // pB (500) -> pC (700) -> pA (900).
  private val hikingProducts = Seq(
    makeProduct("pA", "Men Hiking Boot Alpha", "Footwear", 900.0),
    makeProduct("pB", "Men Hiking Boot Beta", "Footwear", 500.0),
    makeProduct("pC", "Men Hiking Boot Gamma", "Footwear", 700.0)
  )

  private val hikingCall2Json =
    """{
      |  "mode": "recommend",
      |  "filters": { "category": "Footwear", "budget": null, "keywords": ["hiking"], "attributes": { "gender": "men" } },
      |  "assistantResponse": "Here are hiking boots for men.",
      |  "followUpQuestion": null,
      |  "pendingAction": null
      |}""".stripMargin

  /** Envelope-shaped conversation state carrying a pending no-match offer.
    * The pending filters deliberately differ from anything Call #2 returns so
    * the accept test can prove the deterministic re-search used the stored ones.
    */
  private val pendingStateBlob =
    """{
      |  "filters": { "category": "Footwear", "budget": null, "keywords": ["hiking"], "attributes": { "gender": "men" } },
      |  "pending": {
      |    "filters": { "category": "Footwear", "budget": null, "keywords": ["trail"], "attributes": { "gender": "men" } },
      |    "suggestion": "trail running shoes",
      |    "candidateIds": ["pB", "pC", "pA"]
      |  }
      |}""".stripMargin

  test("re-check match verdict returns only the matchedIds subset, in rerank order") {
    val llmClient = new TestLLMClient(
      call2Json = hikingCall2Json,
      call3Json = """{"verdict": "match", "matchedIds": ["pA", "pB"], "suggestion": null}"""
    )
    val provider = new TestProductProvider(hikingProducts)
    val client = new TestRestClient()
    val msgRepo = new FakeMessageRepo(client)
    val service = new AssistantService(
      llmClient, provider, new ConversationStateRepo(client), msgRepo, new ConversationRepo(client)
    )

    val result = service.respond("c1", "hiking boots for men")

    result.isRight shouldBe true
    val turn = result.toOption.get
    // matchedIds were given in reverse of the rerank order (pB before pA) to
    // prove the returned order comes from the reranker, not from matchedIds.
    turn.products.map(_.id) shouldBe Seq("pB", "pA")
    llmClient.call2Count shouldBe 1
    llmClient.call3Count shouldBe 1
    val envelope = msgRepo.lastEnvelope.get
    envelope("pending").isNull shouldBe true
  }

  test("re-check match with empty or unresolvable matchedIds falls back to the full reranked list") {
    for (call3 <- Seq(
           """{"verdict": "match", "matchedIds": [], "suggestion": null}""",
           """{"verdict": "match", "matchedIds": ["does-not-exist"], "suggestion": null}"""
         )) {
      val llmClient = new TestLLMClient(call2Json = hikingCall2Json, call3Json = call3)
      val provider = new TestProductProvider(hikingProducts)
      val client = new TestRestClient()
      val service = new AssistantService(
        llmClient, provider, new ConversationStateRepo(client), new FakeMessageRepo(client), new ConversationRepo(client)
      )

      val result = service.respond("c1", "hiking boots for men")

      result.isRight shouldBe true
      result.toOption.get.products.map(_.id) shouldBe Seq("pB", "pC", "pA")
    }
  }

  test("re-check no_match holds products back and persists a PendingOffer in the state envelope") {
    val llmClient = new TestLLMClient(
      call2Json = hikingCall2Json,
      call3Json = """{"verdict": "no_match", "matchedIds": [], "suggestion": "trail running shoes"}"""
    )
    val provider = new TestProductProvider(hikingProducts)
    val client = new TestRestClient()
    val msgRepo = new FakeMessageRepo(client)
    val service = new AssistantService(
      llmClient, provider, new ConversationStateRepo(client), msgRepo, new ConversationRepo(client)
    )

    val result = service.respond("c1", "waterproof hiking boots for men")

    result.isRight shouldBe true
    val turn = result.toOption.get
    turn.products shouldBe Seq.empty
    turn.reply should include("don't have that exact product")
    turn.reply should include("trail running shoes")
    turn.reply should not include "(Filters:"

    val envelope = msgRepo.lastEnvelope.get
    envelope.obj.keySet shouldBe Set("filters", "pending")
    envelope("filters")("category").str shouldBe "Footwear"
    val pending = envelope("pending")
    pending("suggestion").str shouldBe "trail running shoes"
    pending("candidateIds").arr.map(_.str).toSeq shouldBe Seq("pB", "pC", "pA")
    pending("filters")("category").str shouldBe "Footwear"
    pending("filters")("keywords").arr.map(_.str).toSeq shouldBe Seq("hiking")
  }

  test("accept of a pending offer re-searches deterministically and never re-runs the re-check") {
    val call2AcceptJson =
      """{
        |  "mode": "recommend",
        |  "filters": { "category": "Footwear", "budget": null, "keywords": ["hiking"], "attributes": { "gender": "men" } },
        |  "assistantResponse": "Sure, showing those now.",
        |  "followUpQuestion": null,
        |  "pendingAction": "accept"
        |}""".stripMargin

    val trailProducts = Seq(
      makeProduct("tA", "Men Trail Shoe Alpha", "Footwear", 900.0),
      makeProduct("tB", "Men Trail Shoe Beta", "Footwear", 500.0)
    )
    val llmClient = new TestLLMClient(call2Json = call2AcceptJson)
    val provider = new TestProductProvider(trailProducts)
    val client = new TestRestClient(stateFiltersJson = pendingStateBlob)
    val msgRepo = new FakeMessageRepo(client)
    val service = new AssistantService(
      llmClient, provider, new ConversationStateRepo(client), msgRepo, new ConversationRepo(client)
    )

    val result = service.respond("c1", "yes, show me those")

    result.isRight shouldBe true
    val turn = result.toOption.get
    turn.reply shouldBe "Here are those items — hope one of them works for you."
    turn.products.map(_.id) shouldBe Seq("tB", "tA")
    // The re-search ran with the persisted pending filters, not the Call #2 ones.
    provider.searchCalled shouldBe true
    provider.lastSearchFilters.get.keywords shouldBe List("trail")
    // Call #2 ran once; Call #3 was skipped on the confirmation turn.
    llmClient.call2Count shouldBe 1
    llmClient.call3Count shouldBe 0
    // The pending offer was cleared from the state envelope.
    msgRepo.lastEnvelope.get("pending").isNull shouldBe true
  }

  test("reject of a pending offer clears it without searching") {
    val call2RejectJson =
      """{
        |  "mode": "recommend",
        |  "filters": { "category": "Footwear", "budget": null, "keywords": ["hiking"], "attributes": { "gender": "men" } },
        |  "assistantResponse": "No problem.",
        |  "followUpQuestion": null,
        |  "pendingAction": "reject"
        |}""".stripMargin

    val llmClient = new TestLLMClient(call2Json = call2RejectJson)
    val provider = new TestProductProvider(hikingProducts)
    val client = new TestRestClient(stateFiltersJson = pendingStateBlob)
    val msgRepo = new FakeMessageRepo(client)
    val service = new AssistantService(
      llmClient, provider, new ConversationStateRepo(client), msgRepo, new ConversationRepo(client)
    )

    val result = service.respond("c1", "no, not interested")

    result.isRight shouldBe true
    val turn = result.toOption.get
    turn.reply shouldBe "No problem — let me know what else you'd like to find."
    turn.products shouldBe Seq.empty
    provider.searchCalled shouldBe false
    llmClient.call2Count shouldBe 1
    llmClient.call3Count shouldBe 0
    msgRepo.lastEnvelope.get("pending").isNull shouldBe true
  }

  test("a throwing relevance re-check fails open with the full reranked list") {
    val llmClient = new TestLLMClient(call2Json = hikingCall2Json, call3ShouldThrow = true)
    val provider = new TestProductProvider(hikingProducts)
    val client = new TestRestClient()
    val msgRepo = new FakeMessageRepo(client)
    val service = new AssistantService(
      llmClient, provider, new ConversationStateRepo(client), msgRepo, new ConversationRepo(client)
    )

    val result = service.respond("c1", "hiking boots for men")

    result.isRight shouldBe true
    val turn = result.toOption.get
    turn.products.map(_.id) shouldBe Seq("pB", "pC", "pA")
    turn.reply should include("Here are hiking boots for men.")
    llmClient.call3Count shouldBe 1
    msgRepo.lastEnvelope.get("pending").isNull shouldBe true
  }
}
