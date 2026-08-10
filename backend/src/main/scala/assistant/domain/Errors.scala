package assistant.domain

/** Every failure a service can produce, modeled as data instead of thrown
  * exceptions wherever possible. Routes translate an `AppError` into the
  * exact `{ error, code }` JSON shape + HTTP status code from
  * API_CONTRACT.md — there is exactly one place (`http.ErrorMapping`) that
  * does that translation, so a new error case can't accidentally get the
  * wrong status code in one route but not another.
  */
sealed abstract class AppError(val message: String, val code: String) extends Exception(message)

object AppError {
  final case class BadRequest(msg: String, c: String = "BAD_REQUEST") extends AppError(msg, c)
  final case class Unauthorized(msg: String = "Missing or invalid credentials", c: String = "UNAUTHORIZED")
      extends AppError(msg, c)
  final case class Forbidden(msg: String = "You do not have access to this resource", c: String = "FORBIDDEN")
      extends AppError(msg, c)
  final case class NotFound(msg: String, c: String = "NOT_FOUND") extends AppError(msg, c)
  final case class Conflict(msg: String, c: String) extends AppError(msg, c)

  /** The message failed the regex pre-filter or Gemma Call #1. Per
    * ARCHITECTURE.md §6 this must map to HTTP 422 and must never have been
    * written to `messages` / `conversation_state`.
    */
  final case class Rejected(msg: String = "I can't help with that request. Please ask about shopping or products.")
      extends AppError(msg, "REJECTED")

  final case class AssistantFailed(msg: String = "Something went wrong, please try again.")
      extends AppError(msg, "ASSISTANT_FAILED")

  final case class UpstreamUnavailable(msg: String = "Something went wrong, please try again.")
      extends AppError(msg, "UPSTREAM_UNAVAILABLE")

  final case class Internal(msg: String = "Internal server error") extends AppError(msg, "INTERNAL_ERROR")
}
