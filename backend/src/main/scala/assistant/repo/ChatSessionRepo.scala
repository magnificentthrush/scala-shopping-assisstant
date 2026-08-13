package assistant.repo

import assistant.domain.ChatSession
import assistant.domain.NullableOption.nullableOptionRW
import upickle.default._

/** Read/write access for the `chat_sessions` table only
  * (docs/database-schema.md §"chat_sessions", docs/conversationPlan.md §5).
  * `conversation_id` is nullable — a fresh session has no conversation until
  * the first accepted message lazy-creates one.
  */
class ChatSessionRepo(client: SupabaseRestClient) {
  private val Table: String = "chat_sessions"

  /** Inserts a session row and returns it with DB-generated fields filled in.
    * Pass `conversationId = Some(id)` when the conversation is already known
    * (e.g. `resume`) to avoid a second `setConversationId` round-trip.
    */
  def insert(userId: String, conversationId: Option[String] = None): ChatSession = {
    val row = ChatSessionRepo.NewSessionRow(userId = userId, conversationId = conversationId)
    val json = client.post(Table, write(row))
    read[Seq[ChatSession]](json).headOption.getOrElse(
      throw new RuntimeException(s"Supabase returned no row after inserting session for user $userId")
    )
  }

  /** Returns the session row, or None if the id is missing or not a UUID. */
  def findById(sessionId: String): Option[ChatSession] = {
    if (!isValidUuid(sessionId)) None
    else {
      val json = client.get(Table, Map("id" -> s"eq.$sessionId"))
      read[Seq[ChatSession]](json).headOption
    }
  }

  // Rejects non-UUID ids so PostgREST doesn't 400 on a malformed filter.
  private def isValidUuid(s: String): Boolean =
    scala.util.Try(java.util.UUID.fromString(s)).isSuccess

  /** Points a session at its (now-created) conversation — lazy-create step.
    * Does not recurse/race-protect: checked-then-insert is the documented
    * behaviour for this pass (docs/conversationPlan.md §3).
    */
  def setConversationId(sessionId: String, conversationId: String): Unit = {
    val json = client.patch(
      Table,
      Map("id" -> s"eq.$sessionId"),
      ujson.Obj("conversation_id" -> conversationId).toString
    )
    if (read[Seq[ChatSession]](json).isEmpty) {
      throw new RuntimeException(s"setConversationId matched no session with id $sessionId")
    }
  }

  /** Per-message activity touch: `last_active_at = now()`,
    * `expires_at = now() + 30 minutes` — the "touch activity" half of
    * ARCHITECTURE.md §4 (docs/conversationPlan.md §3). The timestamp is
    * computed here in Scala because PostgREST does not evaluate SQL
    * expressions like `now()` in request bodies.
    */
  def touchActivity(sessionId: String): Unit = {
    val now = java.time.Instant.now()
    val expiresAt = now.plus(30, java.time.temporal.ChronoUnit.MINUTES).toString
    val json = client.patch(
      Table,
      Map("id" -> s"eq.$sessionId"),
      ujson.Obj("last_active_at" -> now.toString, "expires_at" -> expiresAt).toString
    )
    if (read[Seq[ChatSession]](json).isEmpty) {
      throw new RuntimeException(s"touchActivity matched no session with id $sessionId")
    }
  }
}

object ChatSessionRepo {
  /** Insert-only shape: the DB defaults/generates every other column
    * (`id`, `conversation_id` null, `created_at`, `last_active_at`, and
    * `expires_at` null), so those must not be sent in the request body —
    * same pattern as `UserRepo.NewUserRow`.
    */
  private case class NewSessionRow(
      @upickle.implicits.key("user_id") userId: String,
      @upickle.implicits.key("conversation_id") conversationId: Option[String]
  )

  private object NewSessionRow {
    implicit val rw: ReadWriter[NewSessionRow] = macroRW
  }
}