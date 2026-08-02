package assistant.services

import com.google.genai.Client
import com.google.genai.types.GenerateContentResponse

/**
  * Minimal smoke test for Gemma 4 via Google's Java Gen AI SDK (com.google.genai).
  *
  * Run from backend/:
  *   sbt "runMain assistant.services.GemmaLLMClient"
  *
  * Requires GEMMA_API_KEY (or GOOGLE_API_KEY) in the environment.
  * Model: gemma-4-31b-it — see https://ai.google.dev/gemma/docs/core/gemma_on_gemini_api
  */
object GemmaLLMClient {
//   private val ModelId = "gemma-4-31b-it"
  private val ModelId = "gemini-3.5-flash-lite"
  private val Prompt = "who is the current mayor of NYC"

  def main(args: Array[String]): Unit = {
    val apiKey =
      sys.env.get("GEMMA_API_KEY")
        .orElse(sys.env.get("GOOGLE_API_KEY"))
        .getOrElse(sys.error("Set GEMMA_API_KEY or GOOGLE_API_KEY in the environment"))

    val client: Client = Client.builder().apiKey(apiKey).build()

    val response: GenerateContentResponse =
      client.models.generateContent(ModelId, Prompt, null)

    println(response.text())
  }
}
