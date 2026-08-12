package assistant.repo

import assistant.domain.{MessageRow, Product}
import assistant.domain.BigDecimalCodec.bigDecimalRW
import assistant.domain.NullableOption.nullableOptionRW
import upickle.default._

/** Read/write access for the `messages` table
  * (docs/database-schema.md §"messages", docs/conversationPlan.md §5).
  * Sequence numbers are computed with `max(sequence_number) + 1` via PostgREST
  * (`order=sequence_number.desc&limit=1`), not a DB sequence — safe because
  * this pass only ever writes one row per request.
  */
class MessageRepo(client: SupabaseRestClient) {
  private val Table: String = "messages"

  /** The next sequence number for a conversation: `1` for a fresh
    * conversation, `max(sequence_number) + 1` otherwise.
    */
  def nextSequenceNumber(conversationId: String): Int = {
    val json = client.get(
      Table,
      Map(
        "conversation_id" -> s"eq.$conversationId",
        "order" -> "sequence_number.desc",
        "limit" -> "1"
      )
    )
    read[Seq[MessageRow]](json).headOption match {
      case Some(last) => last.sequenceNumber + 1
      case None       => 1
    }
  }

  /** Inserts the user's row and returns it with DB-generated fields (`id`,
    * `created_at`; `filters_snapshot` stays null this pass).
    */
  def insertUserMessage(conversationId: String, content: String): MessageRow = {
    val sequenceNumber = nextSequenceNumber(conversationId)
    val row = MessageRepo.NewUserMessageRow(
      conversationId = conversationId,
      sequenceNumber = sequenceNumber,
      role = "user",
      content = content
    )
    val json = client.post(Table, write(row))
    read[Seq[MessageRow]](json).headOption.getOrElse(
      throw new RuntimeException(
        s"Supabase returned no row after inserting message into conversation $conversationId"
      )
    )
  }

  /** Full history for resume (`POST /api/conversations/{id}/resume`),
    * oldest-first.
    */
  def listByConversation(conversationId: String): Seq[MessageRow] = {
    val json = client.get(
      Table,
      Map("conversation_id" -> s"eq.$conversationId", "order" -> "sequence_number.asc")
    )
    read[Seq[MessageRow]](json)
  }

  /** The most recent `limit` messages for a conversation, ordered chronologically (oldest-first).
    */
  def recent(conversationId: String, limit: Int = 8): Seq[MessageRow] = {
    val json = client.get(
      Table,
      Map(
        "conversation_id" -> s"eq.$conversationId",
        "order" -> "sequence_number.desc",
        "limit" -> limit.toString
      )
    )
    read[Seq[MessageRow]](json).reverse
  }

  /** Inserts the assistant's turn and updates conversation_state.filters atomically via
    * the `commit_assistant_turn` Postgres function (migration 010). `products` is the
    * JSONB snapshot of recommended products so cards survive conversation reload.
    */
  def insertAssistantMessage(
      conversationId: String,
      content: String,
      filters: ujson.Value,
      products: Seq[Product]
  ): MessageRow = {
    val body = ujson.Obj(
      "p_conversation_id" -> conversationId,
      "p_content" -> content,
      "p_filters" -> filters,
      "p_products" -> (if (products.nonEmpty) writeJs(products) else ujson.Null)
    ).render()
    val json = client.rpc("commit_assistant_turn", body)
    read[Seq[MessageRow]](json).headOption.getOrElse(
      throw new RuntimeException(
        s"commit_assistant_turn RPC returned no row for conversation $conversationId"
      )
    )
  }
}

object MessageRepo {
  /** Insert-only shape: `id`, `filters_snapshot`, and `created_at` are
    * DB-generated/defaulted and must not be sent in the request body.
    */
  private case class NewUserMessageRow(
      @upickle.implicits.key("conversation_id") conversationId: String,
      @upickle.implicits.key("sequence_number") sequenceNumber: Int,
      role: String,
      content: String
  )

  private object NewUserMessageRow {
    implicit val rw: ReadWriter[NewUserMessageRow] = macroRW
  }
}