package assistant.repo

import assistant.domain.Conversation
import assistant.domain.NullableOption.nullableOptionRW
import upickle.default._

/** Read/write access for the `conversations` table only
  * (docs/database-schema.md §"conversations", docs/conversationPlan.md §5).
  * Ownership-aware writes filter by `user_id` **inside the query** (the
  * `WHERE` clause is the authz check, per database-schema.md) — a belt-and-
  * suspenders layer under the service-level ownership check.
  */
class ConversationRepo(client: SupabaseRestClient) {
  private val Table: String = "conversations"

  /** Insert-only shape: the DB defaults/generates every other column
    * (`id`, timestamps, nullable `title`), so only `user_id` is sent.
    */
  def insert(userId: String): Conversation = {
    val row = ConversationRepo.NewConversationRow(userId = userId)
    val json = client.post(Table, write(row))
    read[Seq[Conversation]](json).headOption.getOrElse(
      throw new RuntimeException(
        s"Supabase returned no row after inserting conversation for user $userId"
      )
    )
  }

  def findById(conversationId: String): Option[Conversation] = {
    if (!isValidUuid(conversationId)) None
    else {
      val json = client.get(Table, Map("id" -> s"eq.$conversationId"))
      read[Seq[Conversation]](json).headOption
    }
  }

  private def isValidUuid(s: String): Boolean =
    scala.util.Try(java.util.UUID.fromString(s)).isSuccess

  /** Sidebar history for one user, most recently active first — served by the
    * `conversations_last_message_at_idx` index (docs/database-schema.md §Indexes).
    */
  def listByUser(userId: String): Seq[Conversation] = {
    val json = client.get(
      Table,
      Map("user_id" -> s"eq.$userId", "order" -> "last_message_at.desc")
    )
    read[Seq[Conversation]](json)
  }

  /** Rename. Ownership is enforced in the query itself (`WHERE id=eq AND
    * user_id=eq`); `None` means the row is missing or not owned by `userId`.
    * `updated_at` is touched here because this is an explicit user edit.
    */
  def updateTitle(conversationId: String, userId: String, title: String): Option[Conversation] = {
    val now = java.time.Instant.now().toString
    val json = client.patch(
      Table,
      Map("id" -> s"eq.$conversationId", "user_id" -> s"eq.$userId"),
      ujson.Obj("title" -> title, "updated_at" -> now).toString
    )
    read[Seq[Conversation]](json).headOption
  }

  /** Auto-title: set `title` only when it is still NULL. The `title=is.null`
    * filter in the WHERE clause makes this atomic — a row already titled (by
    * an earlier auto-title or a user rename) matches zero rows and is left
    * alone, so a user's manual rename can never be overwritten by this.
    * Returns the updated row, or `None` if the conversation was already titled.
    * Unlike `updateTitle`, this does NOT bump `updated_at` — it's a system
    * fill-in, not a user edit, and shouldn't reorder the sidebar.
    */
  def setTitleIfNull(conversationId: String, title: String): Option[Conversation] = {
    val json = client.patch(
      Table,
      Map("id" -> s"eq.$conversationId", "title" -> "is.null"),
      ujson.Obj("title" -> title).toString
    )
    read[Seq[Conversation]](json).headOption
  }

  /** Hard delete (ON DELETE CASCADE takes messages/state/sessions with it).
    * Returns `true` iff a row owned by `userId` was actually deleted.
    */
  def delete(conversationId: String, userId: String): Boolean = {
    val json = client.delete(
      Table,
      Map("id" -> s"eq.$conversationId", "user_id" -> s"eq.$userId")
    )
    read[Seq[ujson.Value]](json).nonEmpty
  }

  /** `last_message_at = now()` after a committed user turn — drives the
    * `GET /api/conversations` sort order. Timestamp computed in Scala for the
    * same reason as `ChatSessionRepo.touchActivity`.
    */
  def touchLastMessageAt(conversationId: String): Unit = {
    val now = java.time.Instant.now().toString
    val json = client.patch(
      Table,
      Map("id" -> s"eq.$conversationId"),
      ujson.Obj("last_message_at" -> now).toString
    )
    if (read[Seq[Conversation]](json).isEmpty) {
      throw new RuntimeException(s"touchLastMessageAt matched no conversation with id $conversationId")
    }
  }
}

object ConversationRepo {
  private case class NewConversationRow(@upickle.implicits.key("user_id") userId: String)

  private object NewConversationRow {
    implicit val rw: ReadWriter[NewConversationRow] = macroRW
  }
}