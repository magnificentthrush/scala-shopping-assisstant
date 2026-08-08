package assistant.repo

import assistant.db.Database
import assistant.domain.{ConversationState, Filters}
import assistant.domain.JsonCodecs._
import java.sql.Connection
import org.postgresql.util.PGobject

/** Repository for `conversation_state`: one row per conversation, current
  * filters only. This is deliberately the "hot path" table — a primary-key
  * point lookup, never a scan over `messages` — see ARCHITECTURE.md §4.
  */
class ConversationStateRepo(db: Database) {

  private def jsonbParam(json: String): PGobject = {
    val obj = new PGobject()
    obj.setType("jsonb")
    obj.setValue(json)
    obj
  }

  def initialize(conn: Connection, conversationId: String): Unit = {
    val stmt = conn.prepareStatement(
      "INSERT INTO conversation_state (conversation_id, filters) VALUES (?, ?)"
    )
    stmt.setString(1, conversationId)
    stmt.setObject(2, jsonbParam(upickle.default.write(Filters())))
    stmt.executeUpdate()
    stmt.close()
  }

  def get(conversationId: String): Filters = db.withConnection { conn =>
    val stmt = conn.prepareStatement("SELECT filters FROM conversation_state WHERE conversation_id = ?")
    stmt.setString(1, conversationId)
    val rs = stmt.executeQuery()
    try {
      if (rs.next()) upickle.default.read[Filters](rs.getString("filters"))
      else Filters() // No row yet (shouldn't normally happen) -> empty filters, fail soft.
    } finally { rs.close(); stmt.close() }
  }

  /** Overwrites current filters — this table is NOT an audit trail (that's
    * `messages.filters_snapshot`); it always reflects "now".
    */
  def update(conn: Connection, conversationId: String, filters: Filters): Unit = {
    val stmt = conn.prepareStatement(
      "UPDATE conversation_state SET filters = ?, updated_at = now() WHERE conversation_id = ?"
    )
    stmt.setObject(1, jsonbParam(upickle.default.write(filters)))
    stmt.setString(2, conversationId)
    stmt.executeUpdate()
    stmt.close()
  }
}
