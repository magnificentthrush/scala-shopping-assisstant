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

/** Temporary `200` body for a Call #1 validation pass
  * (docs/call1Plan.md §4, docs/API_CONTRACT.md §Messages). Proves the
  * regex pre-filter → Call #1 wiring end-to-end; it is NOT a chat reply
  * and has no `mode`/`products`/message history. Replaced by the full
  * assistant shape when Call #2 lands.
  *
  * `safe` is always `true` for this body — a rejected turn is a `422`
  * `ErrorBody` with `code: "REJECTED"`, never this response.
  */
case class ValidationPassResponse(safe: Boolean, sessionId: String, message: String)

object ValidationPassResponse {
  implicit val rw: ReadWriter[ValidationPassResponse] = macroRW
}
