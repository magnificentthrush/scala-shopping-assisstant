package assistant.domain

import upickle.default._
import assistant.domain.NullableOption.nullableOptionRW

/** Raw Call #2 output, parsed from the LLM's JSON — internal, not serialized directly. */
case class AssistantLLMResult(
    mode: String,
    filters: ExtractedFilters,
    assistantResponse: String,
    followUpQuestion: Option[String]
)

/** AssistantService.respond's internal success return value — mirrors CommittedTurn. */
case class AssistantTurnResult(
    mode: String,
    reply: String,
    followUpQuestion: Option[String],
    products: Seq[Product],
    assistantMessage: MessageResponse
)

/** The real 200 response for POST /api/sessions/{sessionId}/messages
  * matching API_CONTRACT.md target shape.
  */
case class SendMessageResponse(
    sessionId: String,
    conversationId: String,
    mode: String,
    reply: String,
    followUpQuestion: Option[String],
    products: Seq[Product],
    userMessage: MessageResponse,
    assistantMessage: MessageResponse
)

object SendMessageResponse {
  implicit val rw: ReadWriter[SendMessageResponse] = macroRW
}
