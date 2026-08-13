package assistant.services

import assistant.domain.{ValidationFailure}

import scala.util.{Failure, Success, Try}

/** Orchestrates the Call #1 validation pipeline for one user message
  * (docs/call1Plan.md §5). Routes stay thin: they parse the request, call
  * `validate`, and turn the `Either` into a status + JSON body — no
  * pipeline logic in the HTTP layer.
  *
  * Pipeline order is mandatory (docs/ARCHITECTURE.md §6):
  *   1. Blank / whitespace-only message → `400`, 0 LLM calls.
  *   2. `RegexPreFilter` match → `422 REJECTED`, 0 LLM calls.
  *   3. Call #1 (`PromptValidator`), fail-closed → `422 REJECTED` on
  *      `safe:false` or any failure.
  *   4. `safe:true` → `Right(())` — the message is approved, nothing more.
  *      Callers (e.g. `ConversationService.commitUserTurn`) decide what to do
  *      with the approved message (docs/conversationPlan.md §8 task 10).
  *
  * `validate` never throws: an unexpected exception is treated exactly like
  * a fail-closed rejection (never a pass, never a 500).
  */
class MessageValidationService(client: LLMClient) {

  private val RejectionError =  //user in rejection in the bottom
    "I can't help with that request. Please ask about shopping or products."
  private val RejectionCode = Some("REJECTED") //user in rejection in the bottom

  /** Runs the Call #1 pipeline (blank, regex, LLM) and never throws. */
  def validate(
      message: String
  ): Either[ValidationFailure, Unit] =
    Try(validateUnsafe(message)) match {
      case Success(result) => result
      case Failure(_)      => Left(rejected)
    }

  // The actual pipeline; callers wrap this so unexpected exceptions fail closed.
  private def validateUnsafe(
      message: String
  ): Either[ValidationFailure, Unit] = {
    if (message.trim.isEmpty)
      Left(ValidationFailure(status = 400, error = "Please provide a message", code = None))
    else if (RegexPreFilter.isBlocked(message))
      Left(rejected)
    else {
      val result = PromptValidator.validate(message, client)
      if (result.safe) Right(())
      else Left(rejected)
    }
  }

  // Shared 422 REJECTED payload for regex hits and unsafe LLM classifications.
  private def rejected: ValidationFailure =
    ValidationFailure(status = 422, error = RejectionError, code = RejectionCode)
}
