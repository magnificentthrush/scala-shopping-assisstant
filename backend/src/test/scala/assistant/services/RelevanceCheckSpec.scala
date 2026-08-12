package assistant.services

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class RelevanceCheckSpec extends AnyFunSuite with Matchers {

  test("parses a match verdict with matchedIds") {
    val raw =
      """{
        |  "verdict": "match",
        |  "matchedIds": ["p1", "p3"],
        |  "suggestion": null
        |}""".stripMargin

    val verdict = RelevanceCheck.parse(raw)

    verdict.verdict shouldBe "match"
    verdict.matchedIds shouldBe Seq("p1", "p3")
    verdict.suggestion shouldBe None
  }

  test("parses a no_match verdict with a suggestion") {
    val raw =
      """{
        |  "verdict": "no_match",
        |  "matchedIds": [],
        |  "suggestion": "trail running shoes from Wildcraft or Nike"
        |}""".stripMargin

    val verdict = RelevanceCheck.parse(raw)

    verdict.verdict shouldBe "no_match"
    verdict.matchedIds shouldBe empty
    verdict.suggestion shouldBe Some("trail running shoes from Wildcraft or Nike")
  }

  test("parses JSON wrapped in markdown code fences") {
    val raw =
      """```json
        |{
        |  "verdict": "match",
        |  "matchedIds": ["a1"],
        |  "suggestion": null
        |}
        |```""".stripMargin

    val verdict = RelevanceCheck.parse(raw)

    verdict.verdict shouldBe "match"
    verdict.matchedIds shouldBe Seq("a1")
  }

  test("throws on malformed input, missing verdict, and invalid verdict string") {
    an[Exception] should be thrownBy RelevanceCheck.parse("no json here at all")
    an[Exception] should be thrownBy RelevanceCheck.parse("""{"matchedIds": ["p1"], "suggestion": null}""")
    an[Exception] should be thrownBy
      RelevanceCheck.parse("""{"verdict": "maybe", "matchedIds": [], "suggestion": null}""")
  }

  test("verdict parsing is case-insensitive and tolerant of surrounding whitespace") {
    RelevanceCheck.parse("""{"verdict": "  MATCH ", "matchedIds": ["x"]}""").verdict shouldBe "match"
    RelevanceCheck.parse("""{"verdict": "No_Match", "matchedIds": []}""").verdict shouldBe "no_match"
  }

  test("match without matchedIds is allowed; missing or blank suggestion becomes None") {
    val noIds = RelevanceCheck.parse("""{"verdict": "match"}""")
    noIds.verdict shouldBe "match"
    noIds.matchedIds shouldBe empty
    noIds.suggestion shouldBe None

    val blankSuggestion = RelevanceCheck.parse("""{"verdict": "no_match", "matchedIds": [], "suggestion": "   "}""")
    blankSuggestion.suggestion shouldBe None
  }

  test("blank entries inside matchedIds are trimmed and dropped") {
    val verdict = RelevanceCheck.parse("""{"verdict": "match", "matchedIds": [" p1 ", "", "  ", "p2"]}""")
    verdict.matchedIds shouldBe Seq("p1", "p2")
  }
}
