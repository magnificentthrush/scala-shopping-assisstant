package assistant.services

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class AssistantPromptSpec extends AnyFunSuite with Matchers {

  test("parses well-formed JSON into AssistantLLMResult") {
    val rawJson =
      """{
        |  "mode": "recommend",
        |  "filters": {
        |    "category": "Footwear",
        |    "budget": 120.0,
        |    "keywords": ["hiking", "waterproof"],
        |    "attributes": { "color": "black" }
        |  },
        |  "assistantResponse": "Here are waterproof hiking boots under $120.",
        |  "followUpQuestion": "Do you prefer mid-cut or low-cut?"
        |}""".stripMargin

    val result = AssistantPrompt.parse(rawJson)

    result.mode shouldBe "recommend"
    result.filters.category shouldBe Some("Footwear")
    result.filters.budget shouldBe Some(BigDecimal("120.0"))
    result.filters.keywords shouldBe List("hiking", "waterproof")
    result.filters.attributes shouldBe Map("color" -> "black")
    result.assistantResponse shouldBe "Here are waterproof hiking boots under $120."
    result.followUpQuestion shouldBe Some("Do you prefer mid-cut or low-cut?")
  }

  test("parses JSON wrapped in markdown code fences") {
    val rawText =
      """```json
        |{
        |  "mode": "clarify",
        |  "filters": {
        |    "category": null,
        |    "budget": null,
        |    "keywords": [],
        |    "attributes": {}
        |  },
        |  "assistantResponse": "Could you clarify what kind of shoes you need?",
        |  "followUpQuestion": null
        |}
        |```""".stripMargin

    val result = AssistantPrompt.parse(rawText)

    result.mode shouldBe "clarify"
    result.filters.category shouldBe None
    result.filters.budget shouldBe None
    result.filters.keywords shouldBe Nil
    result.assistantResponse shouldBe "Could you clarify what kind of shoes you need?"
    result.followUpQuestion shouldBe None
  }

  test("parses alternative reply field key") {
    val rawText =
      """{
        |  "mode": "info",
        |  "filters": {},
        |  "reply": "Our return policy allows 30 days."
        |}""".stripMargin

    val result = AssistantPrompt.parse(rawText)

    result.mode shouldBe "info"
    result.assistantResponse shouldBe "Our return policy allows 30 days."
  }

  test("throws exception on malformed or non-JSON text") {
    an[Exception] should be thrownBy {
      AssistantPrompt.parse("Not a JSON string")
    }
  }

  test("throws exception when mandatory assistantResponse field is missing") {
    val rawJson = """{ "mode": "recommend", "filters": {} }"""
    an[Exception] should be thrownBy {
      AssistantPrompt.parse(rawJson)
    }
  }
}
