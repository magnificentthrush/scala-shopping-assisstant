package assistant.services

/**
  * The single seam every LLM call goes through (Call #1 validation, Call #2
  * assistant). Business logic depends only on this interface — swapping the
  * underlying provider or model policy later is a one-file change, the same
  * reasoning already applied to `ProductProvider` for product retrieval.
  */
trait LLMClient {

  /** Generate content for a single prompt. Implementations own their own
    * primary/fallback model policy; callers only see the final text and
    * which model actually served the request (for logging).
    *
    * Implementations should let failures propagate as exceptions — callers
    * (e.g. `PromptValidator`) are responsible for fail-closed handling.
    */
  def generate(prompt: String): LLMResponse
}

/** @param text the raw text returned by the model
  * @param modelUsed which model actually served the call (primary or fallback),
  *                   for cost/debugging logs
  */
case class LLMResponse(text: String, modelUsed: String)
