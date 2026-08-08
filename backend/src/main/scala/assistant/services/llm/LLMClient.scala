package assistant.services.llm

import assistant.domain.Filters

/** The interface `ChatService` depends on instead of calling Gemma's HTTP
  * API directly. Same reasoning as `ProductProvider`: swapping LLM
  * providers later (a different model, a mock for tests) is a new
  * implementation of this trait, not a rewrite of the pipeline that calls
  * it.
  *
  * Two methods, matching the two calls in ARCHITECTURE.md §6 exactly —
  * they are separate methods (not one generic "chat" method) because their
  * prompts, output contracts, and failure handling are intentionally
  * different and must never be conflated:
  *
  *   - `validate`  = Call #1, VALIDATION ONLY, narrow single-purpose prompt.
  *   - `assist`    = Call #2, ASSISTANT ONLY, independently hardened prompt.
  */
trait LLMClient {

  /** Returns `Right(ValidationResult)` when Gemma responded and its
    * response parsed as valid JSON with a `safe` field. Returns `Left`
    * (an error description) on any failure: timeout, non-2xx HTTP status,
    * malformed JSON, or a missing `safe` field. Per ARCHITECTURE.md §6,
    * `Left` and `Right(ValidationResult(safe = false, ...))` are handled
    * IDENTICALLY by the caller — this method just distinguishes them for
    * logging purposes.
    */
  def validate(userMessage: String, recentContext: String): Either[String, ValidationResult]

  /** Returns `Right(AssistResult)` on success, `Left` on any failure
    * (timeout, bad status, malformed JSON, missing fields). The caller
    * (ChatService) must turn a `Left` here into `AppError.AssistantFailed`
    * — a generic "something went wrong" — never a stale or partial
    * response.
    */
  def assist(userMessage: String, recentContext: String, currentFilters: Filters): Either[String, AssistResult]
}

final case class ValidationResult(safe: Boolean, reason: String, latencyMs: Long, inputTokens: Int, outputTokens: Int)

final case class AssistResult(
    filters: Filters,
    assistantResponse: String,
    latencyMs: Long,
    inputTokens: Int,
    outputTokens: Int
)
