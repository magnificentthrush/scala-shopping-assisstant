package assistant.domain

import upickle.default._

/**
  * The output contract of Call #1 (docs/ARCHITECTURE.md §6):
  *
  *   {"safe": true, "reason": ""}
  *   {"safe": false, "reason": "Prompt injection detected."}
  *
  * `safe = false` means "reject the turn"; this includes both genuine LLM
  * verdicts and fail-closed defaults (parse failure, missing field, API
  * error, timeout) — callers cannot tell the difference from this type
  * alone, which is intentional: both are treated identically downstream.
  */
case class ValidationResult(safe: Boolean, reason: String)

object ValidationResult {
  implicit val rw: ReadWriter[ValidationResult] = macroRW
}
