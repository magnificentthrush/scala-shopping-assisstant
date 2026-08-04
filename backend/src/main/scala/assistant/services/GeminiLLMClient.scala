package assistant.services

import com.google.genai.Client
import com.google.genai.errors.ApiException

/**
  * The concrete `LLMClient` backed by the Google Gen AI Java SDK (com.google.genai).
  *
  * Model policy (see docs/ARCHITECTURE.md §1): every call tries the primary
  * model first and automatically retries with the fallback model on
  * quota/rate-limit errors. Callers never branch on model choice — they only
  * see the resulting `LLMResponse`, including which model actually served it.
  *
  * Both the primary and fallback models are on a **15 requests/minute** quota
  * (Google AI Studio free tier), i.e. roughly one request every 4s. To stay
  * safely under that, calls to each model are throttled to at least
  * `minRequestIntervalMs` (default 5s) apart, tracked independently per
  * model so a burst of fallback calls doesn't wait on the primary model's
  * timer, and vice versa.
  *
  * SDK reference (BTCA: googleapis/java-genai):
  *   Client.builder().apiKey(...).build()
  *   client.models.generateContent(model, prompt, null)
  *   Quota/rate-limit errors: ApiException with code 429 or status RESOURCE_EXHAUSTED
  */
class GeminiLLMClient(
    apiKey: String,
    primaryModel: String = GeminiLLMClient.PrimaryModel,
    fallbackModel: String = GeminiLLMClient.FallbackModel,
    minRequestIntervalMs: Long = GeminiLLMClient.MinRequestIntervalMs
) extends LLMClient {

  private val client = Client.builder().apiKey(apiKey).build()

  /** Last call time per model, so primary and fallback are throttled independently. */
  private val lastCallAtByModel = scala.collection.mutable.Map.empty[String, Long]

  private def isQuotaError(e: ApiException): Boolean =
    e.code() == 429 || e.status() == "RESOURCE_EXHAUSTED"

  /** Blocks until at least `minRequestIntervalMs` have passed since the last
    * call to this specific model. Coarse-grained (synchronizes on the whole
    * client) — fine while calls are made one at a time per request.
    */
  private def throttle(model: String): Unit = synchronized {
    val now = System.currentTimeMillis()
    val waitMs = lastCallAtByModel.get(model) match {
      case Some(last) => minRequestIntervalMs - (now - last)
      case None       => 0L
    }
    if (waitMs > 0) {
      println(s"[llm] throttling ${waitMs}ms before next call to $model (rate-limit guard)")
      Thread.sleep(waitMs)
    }
    lastCallAtByModel(model) = System.currentTimeMillis()
  }

  private def callModel(model: String, prompt: String) = {
    throttle(model)
    client.models.generateContent(model, prompt, null)
  }

  /** Try the primary model; on quota/rate-limit, retry once with the fallback. */
  def generate(prompt: String): LLMResponse =
    try {
      val response = callModel(primaryModel, prompt)
      LLMResponse(response.text(), primaryModel)
    } catch {
      case e: ApiException if isQuotaError(e) =>
        println(
          s"[llm] primary quota/rate limit (${e.code()} ${e.status()}), falling back to $fallbackModel"
        )
        val response = callModel(fallbackModel, prompt)
        LLMResponse(response.text(), fallbackModel)
    }
}

object GeminiLLMClient {
  val PrimaryModel = "gemini-3.5-flash-lite"
  val FallbackModel = "gemma-4-31b-it"

  /** Both models are rate-limited to 15 req/min; 5s spacing keeps every call
    * comfortably under that (15 RPM allows one every 4s).
    */
  val MinRequestIntervalMs = 5000L

  private val DefaultPrompt = "What is the capital of France?"

  private def apiKeyFromEnv(): String =
    sys.env
      .get("GEMMA_API_KEY")
      .orElse(sys.env.get("GOOGLE_API_KEY"))
      .getOrElse(sys.error("Set GEMMA_API_KEY or GOOGLE_API_KEY in the environment"))

  /** Manual smoke test.
    *
    * Run from backend/:
    *   sbt "runMain assistant.services.GeminiLLMClient"
    *
    * Requires GEMMA_API_KEY (or GOOGLE_API_KEY) in the environment.
    */
  def main(args: Array[String]): Unit = {
    val prompt = args.headOption.getOrElse(DefaultPrompt)
    val client = new GeminiLLMClient(apiKeyFromEnv())
    val response = client.generate(prompt)

    println(s"[llm] served by: ${response.modelUsed}")
    println(response.text)
  }
}
