package assistant.services

import com.google.genai.Client
import com.google.genai.errors.ApiException
import com.google.genai.types.GenerateContentResponse

/**
  * Smoke test for Google Gen AI Java SDK (com.google.genai).
  *
  * SDK reference (BTCA: googleapis/java-genai):
  *   Client.builder().apiKey(...).build()
  *   client.models.generateContent(model, prompt, null)
  *   Quota/rate-limit errors: ApiException with code 429 or status RESOURCE_EXHAUSTED
  *
  * Run from backend/:
  *   sbt "runMain assistant.services.GemmaLLMClient"
  *
  * Requires GEMMA_API_KEY (or GOOGLE_API_KEY) in the environment.
  */
object GemmaLLMClient {
  private val PrimaryModel = "gemini-3.5-flash-lite"
  private val FallbackModel = "gemma-4-31b-it"
  private val DefaultPrompt = "What is the capital of France?"

  private def isQuotaError(e: ApiException): Boolean =
    e.code() == 429 || e.status() == "RESOURCE_EXHAUSTED"

  private def generateContent(
      client: Client,
      model: String,
      prompt: String
  ): GenerateContentResponse =
    client.models.generateContent(model, prompt, null)

  /** Try primary model; on quota/rate-limit, retry with fallback. */
  def generateWithFallback(
      client: Client,
      prompt: String,
      primaryModel: String = PrimaryModel,
      fallbackModel: String = FallbackModel
  ): GenerateContentResponse =
    try {
      println(s"[llm] trying primary model: $primaryModel")
      generateContent(client, primaryModel, prompt)
    } catch {
      case e: ApiException if isQuotaError(e) =>
        println(
          s"[llm] primary quota/rate limit (${e.code()} ${e.status()}), falling back to $fallbackModel"
        )
        generateContent(client, fallbackModel, prompt)
    }

  def main(args: Array[String]): Unit = {
    val apiKey =
      sys.env
        .get("GEMMA_API_KEY")
        .orElse(sys.env.get("GOOGLE_API_KEY"))
        .getOrElse(sys.error("Set GEMMA_API_KEY or GOOGLE_API_KEY in the environment"))

    val prompt = args.headOption.getOrElse(DefaultPrompt)
    val client = Client.builder().apiKey(apiKey).build()
    val response = generateWithFallback(client, prompt)

    println(response.text())
  }
}
