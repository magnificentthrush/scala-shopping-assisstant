package assistant.domain

import upickle.default._

/** `POST /api/sessions/{sessionId}/messages` request body
  * (docs/API_CONTRACT.md §Messages). Only the user's free-text message
  * travels in the body; `sessionId` is a path parameter, not a field here.
  */
case class SendMessageRequest(message: String)

object SendMessageRequest {
  implicit val rw: ReadWriter[SendMessageRequest] = macroRW
}

/** `POST /api/sessions/{sessionId}/messages` pass response
  * (docs/API_CONTRACT.md §Messages, docs/conversationPlan.md §6). The
  * temporary `200` body from `call1Plan.md` — `{ safe, sessionId, message }`
  * — extended with the real `conversationId` and the persisted
  * `userMessage`, because the message is now genuinely written to
  * `messages` (docs/conversationPlan.md §4).
  *
  * `safe` is always `true` for this body — a rejected turn is a `422`
  * `ErrorBody` with `code: "REJECTED"`, never this response. No
  * `mode`/`reply`/`products`/`assistantMessage` yet: those arrive with
  * Call #2.
  */
case class ValidationPassResponse(
    safe: Boolean,
    sessionId: String,
    conversationId: String,
    message: String,
    userMessage: MessageResponse
)

object ValidationPassResponse {
  implicit val rw: ReadWriter[ValidationPassResponse] = macroRW
}

/** Failure returned by `MessageValidationService` — routes map
  * `status`/`error`/`code` straight onto the HTTP response / `ErrorBody`
  * (e.g. `400` blank message, `422` with `code: "REJECTED"`). Internal
  * control-flow type: never serialized directly, so no `ReadWriter`.
  */
final case class ValidationFailure(status: Int, error: String, code: Option[String])
