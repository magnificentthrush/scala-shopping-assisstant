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
    override def get(table: String, params: Map[String, String]): String = {
      if (table == "conversation_state") {
        """[{"conversation_id":"c1","filters":{},"updated_at":"2026-08-11T10:00:00Z"}]"""
      } else if (table == "messages") {
        """[]"""
      } else {
        "[]"
      }
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
        |  "filters": { "category": "Footwear", "budget": 100.0, "keywords": ["hiking"], "attributes": {} },
        |  "assistantResponse": "Here are boots under $100.",
        |  "followUpQuestion": null
        |}""".stripMargin

    val llmClient = new TestLLMClient(llmJson)
    val products = Seq(makeProduct("p1", "Hiking Boot", "Footwear", 89.99))
    val provider = new TestProductProvider(products)
    val client = new TestRestClient()
    val stateRepo = new ConversationStateRepo(client)
    val msgRepo = new MessageRepo(client)

    val service = new AssistantService(llmClient, provider, stateRepo, msgRepo)
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
        |  "filters": { "category": "Footwear", "keywords": ["nonexistent"], "attributes": {} },
        |  "assistantResponse": "No boots found.",
        |  "followUpQuestion": null
        |}""".stripMargin

    val llmClient = new TestLLMClient(llmJson)
    val provider = new TestProductProvider(Seq.empty)
    val client = new TestRestClient()
    val stateRepo = new ConversationStateRepo(client)
    val msgRepo = new MessageRepo(client)

    val service = new AssistantService(llmClient, provider, stateRepo, msgRepo)
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
        |  "filters": { "category": "Watches", "keywords": ["watch"], "attributes": {} },
        |  "assistantResponse": "Here are watches.",
        |  "followUpQuestion": null
        |}""".stripMargin

    val llmClient = new TestLLMClient(llmJson)
    val provider = new TestProductProvider(Seq(makeProduct("p1", "Men's Watch", "Watches", 999.0)))
    val client = new TestRestClient()
    val stateRepo = new ConversationStateRepo(client)
    val msgRepo = new MessageRepo(client)

    val service = new AssistantService(llmClient, provider, stateRepo, msgRepo)
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

    val service = new AssistantService(llmClient, provider, stateRepo, msgRepo)
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

    val service = new AssistantService(llmClient, provider, stateRepo, msgRepo)
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

    val service = new AssistantService(llmClient, provider, stateRepo, msgRepo)
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

    val service = new AssistantService(llmClient, provider, stateRepo, msgRepo)
    val result = service.respond("c1", "Find shoes")

    result.isLeft shouldBe true
    val failure = result.left.toOption.get
    failure.status shouldBe 503
    failure.code shouldBe Some("UPSTREAM_UNAVAILABLE")
  }
}
