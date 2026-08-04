package assistant.services

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import upickle.default._

import scala.io.Source

/** One labeled dummy prompt from `prompt_injection_fixtures.json`. */
case class PromptFixture(id: String, message: String, expectedSafe: Boolean)

object PromptFixture {
  implicit val rw: ReadWriter[PromptFixture] = macroRW
}

/**
  * Runs the dummy prompt fixtures (`src/test/resources/prompt_injection_fixtures.json`)
  * through Call #1 against the real Gemini/Gemma API, and checks the verdict
  * against the label each fixture was created with.
  *
  * This is the dummy-data harness for Call #1: today it feeds
  * `PromptValidator.validate` a hand-labeled set of prompts instead of a real
  * incoming message from `POST /api/sessions/{sessionId}/messages`. Once that
  * endpoint exists, the same `PromptValidator.validate` call is made with the
  * real message instead — this test's use of `PromptValidator` does not change.
  *
  * Requires `GEMMA_API_KEY` or `GOOGLE_API_KEY`. Cancels (does not fail) when
  * neither is set, so `sbt test` stays green in environments without network
  * access or a configured key.
  */
class PromptValidatorLiveSpec extends AnyFunSuite with Matchers {

  private val apiKey = sys.env.get("GEMMA_API_KEY").orElse(sys.env.get("GOOGLE_API_KEY"))

  private def loadFixtures(): Seq[PromptFixture] = {
    val stream = getClass.getClassLoader.getResourceAsStream("prompt_injection_fixtures.json")
    val json = Source.fromInputStream(stream)("UTF-8").mkString
    read[Seq[PromptFixture]](json)
  }

  if (apiKey.isEmpty) {
    test("Call #1 classifies dummy fixtures as expected (skipped: no API key)") {
      cancel("GEMMA_API_KEY / GOOGLE_API_KEY not set; skipping live Call #1 validation tests.")
    }
  } else {
    val client = new GeminiLLMClient(apiKey.get)

    loadFixtures().foreach { fixture =>
      test(s"Call #1 classifies [${fixture.id}] as expectedSafe=${fixture.expectedSafe}") {
        val result = PromptValidator.validate(fixture.message, client)
        withClue(s"""message="${fixture.message}" reason="${result.reason}" """) {
          result.safe shouldBe fixture.expectedSafe
        }
      }
    }
  }
}
