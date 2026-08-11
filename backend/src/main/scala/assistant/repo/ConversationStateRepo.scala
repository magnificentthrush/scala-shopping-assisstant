package assistant.repo

import assistant.domain.ConversationState
import assistant.domain.NullableOption.nullableOptionRW
import upickle.default._

/** Read/write access for the `conversation_state` table
  * (docs/database-schema.md §"conversation_state"). One row per conversation;
  * lives the "current filters" which this pass only ever creates. `filters`
  * stays `'{}'` for the whole of this pass — nothing updates it until Call #2
  * (docs/conversationPlan.md §5).
  */
class ConversationStateRepo(client: SupabaseRestClient) {
  private val Table: String = "conversation_state"

  /** Fetches the conversation state row for the given `conversationId`, if it exists.
    */
  def find(conversationId: String): Option[ConversationState] = {
    val json = client.get(Table, Map("conversation_id" -> s"eq.$conversationId"))
    read[Seq[ConversationState]](json).headOption
  }

  /** Lazy-create step: the DB fills in `filters` (`default '{}'`) and
    * `updated_at` (`default now()`), so only `conversation_id` is sent.
    */
  def insertEmpty(conversationId: String): Unit = {
    val row = ConversationStateRepo.NewStateRow(conversationId = conversationId)
    val json = client.post(Table, write(row))
    if (read[Seq[ConversationState]](json).isEmpty) {
      throw new RuntimeException(
        s"Supabase returned no row after creating state for conversation $conversationId"
      )
    }
  }
}

object ConversationStateRepo {
  private case class NewStateRow(@upickle.implicits.key("conversation_id") conversationId: String)

  private object NewStateRow {
    implicit val rw: ReadWriter[NewStateRow] = macroRW
  }
}