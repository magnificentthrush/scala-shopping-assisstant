package assistant.domain

import upickle.default._
import upickle.implicits.key
import assistant.domain.NullableOption.nullableOptionRW

/** The `conversations` row as Supabase's PostgREST returns/expects it (see
  * docs/database-schema.md §"conversations" and docs/conversationPlan.md §5).
  * Field names are camelCase in Scala; `@key` maps each one to the snake_case
  * column PostgREST uses on the wire, same pattern as `User`. `title` is
  * nullable — an untitled chat is valid. `lastMessageAt` drives the sort order
  * of `GET /api/conversations`.
  */
case class Conversation(
    id: String,
    @key("user_id") userId: String,
    title: Option[String],
    @key("created_at") createdAt: String,
    @key("updated_at") updatedAt: String,
    @key("last_message_at") lastMessageAt: String
)

object Conversation {
  implicit val rw: ReadWriter[Conversation] = macroRW
}

/** The `conversation_state` row (docs/database-schema.md
  * §"conversation_state"). `filters` is stored/read as a raw JSON string;
  * always `"{}"` this pass.
  */
case class ConversationState(
    @key("conversation_id") conversationId: String,
    filters: ujson.Value,
    @key("updated_at") updatedAt: String
)

object ConversationState {
  implicit val rw: ReadWriter[ConversationState] = macroRW
}

/** The `chat_sessions` row (docs/database-schema.md §"chat_sessions"). The
  * ephemeral runtime handle; the `sessionId` the frontend sends is a
  * `chat_sessions.id`. `conversationId` is nullable — a fresh session has no
  * conversation until the first accepted message lazy-creates one.
  */
case class ChatSession(
    id: String,
    @key("conversation_id") conversationId: Option[String],
    @key("user_id") userId: String,
    @key("created_at") createdAt: String,
    @key("last_active_at") lastActiveAt: String,
    @key("expires_at") expiresAt: Option[String]
)

object ChatSession {
  implicit val rw: ReadWriter[ChatSession] = macroRW
}

/** The `messages` row as PostgREST returns it (docs/database-schema.md
  * §"messages"). DB shape, not the API shape — see `MessageResponse` for what
  * the wire returns.
  */
case class MessageRow(
    id: String,
    @key("conversation_id") conversationId: String,
    @key("sequence_number") sequenceNumber: Int,
    role: String,
    content: String,
    @key("filters_snapshot") filtersSnapshot: Option[ujson.Value] = None,
    @key("products") products: Option[Seq[Product]] = None,
    @key("created_at") createdAt: String
)

object MessageRow {
  implicit val rw: ReadWriter[MessageRow] = macroRW
}

/** The API-facing message shape (docs/API_CONTRACT.md §Shared types, §Messages):
  * id, role, content, sequenceNumber, createdAt, products.
  */
case class MessageResponse(
    id: String,
    role: String,
    content: String,
    sequenceNumber: Int,
    createdAt: String,
    products: Seq[Product] = Seq.empty
)

object MessageResponse {
  implicit val rw: ReadWriter[MessageResponse] = macroRW
}

/** A row in the sidebar list (docs/API_CONTRACT.md §Shared types). */
case class ConversationSummary(
    id: String,
    title: Option[String],
    createdAt: String,
    updatedAt: String,
    lastMessageAt: String
)

object ConversationSummary {
  implicit val rw: ReadWriter[ConversationSummary] = macroRW
}

/** `POST /api/conversations` — start new chat (docs/conversationPlan.md §6).
  * `conversationId` is `null`: starting a chat only creates a `chat_sessions`
  * row; the `conversations` row is lazy-created on the first accepted message.
  */
case class StartConversationResponse(
    conversationId: Option[String],
    sessionId: String,
    title: Option[String],
    messages: Seq[MessageResponse]
)

object StartConversationResponse {
  implicit val rw: ReadWriter[StartConversationResponse] = macroRW
}

/** `GET /api/conversations` — list history, ordered by lastMessageAt DESC. */
case class ConversationsListResponse(conversations: Seq[ConversationSummary])

object ConversationsListResponse {
  implicit val rw: ReadWriter[ConversationsListResponse] = macroRW
}

/** `POST /api/conversations/{conversationId}/resume` — new session handle plus
  * the full conversation history (docs/API_CONTRACT.md §Conversations).
  */
case class ResumeConversationResponse(
    conversationId: String,
    sessionId: String,
    title: Option[String],
    messages: Seq[MessageResponse]
)

object ResumeConversationResponse {
  implicit val rw: ReadWriter[ResumeConversationResponse] = macroRW
}

/** `PATCH /api/conversations/{conversationId}` request body (docs/conversationPlan.md §5). */
case class RenameConversationRequest(title: String)

object RenameConversationRequest {
  implicit val rw: ReadWriter[RenameConversationRequest] = macroRW
}

/** Internal service result of committing a user turn — never serialized
  * directly, so no `ReadWriter`. `ConversationService.commitUserTurn` returns
  * this; `MessageRoutes` builds the extended §6 response from it.
  */
case class CommittedTurn(conversationId: String, userMessage: MessageResponse)
