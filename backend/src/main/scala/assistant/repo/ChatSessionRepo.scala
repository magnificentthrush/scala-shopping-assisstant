package assistant.repo

import assistant.db.Database
import assistant.domain.ChatSession
import java.sql.{Connection, ResultSet}

/** Repository for `chat_sessions` — the ephemeral runtime handle described
  * in ARCHITECTURE.md §4. The `sessionId` the frontend holds and sends on
  * every `POST /api/sessions/{sessionId}/messages` call IS a
  * `chat_sessions.id`. It is a separate concept from `conversations.id`,
  * which is the durable, user-facing chat history.
  */
class ChatSessionRepo(db: Database) {

  private def fromRow(rs: ResultSet): ChatSession = ChatSession(
    id = rs.getString("id"),
    conversationId = rs.getString("conversation_id"),
    userId = rs.getString("user_id"),
    createdAt = rs.getTimestamp("created_at").toInstant,
    lastActiveAt = rs.getTimestamp("last_active_at").toInstant,
    expiresAt = Option(rs.getTimestamp("expires_at")).map(_.toInstant)
  )

  /** Creates a new session row against `conversationId`. Used both by
    * "start new chat" AND by "resume" — resuming a past conversation is
    * exactly this: mint a brand new session row against the SAME
    * conversation_id, touching nothing else.
    */
  def insert(conn: Connection, conversationId: String, userId: String): ChatSession = {
    val stmt = conn.prepareStatement(
      "INSERT INTO chat_sessions (conversation_id, user_id) VALUES (?, ?) RETURNING *"
    )
    stmt.setString(1, conversationId)
    stmt.setString(2, userId)
    val rs = stmt.executeQuery()
    try { rs.next(); fromRow(rs) }
    finally { rs.close(); stmt.close() }
  }

  def findById(id: String): Option[ChatSession] = db.withConnection { conn =>
    val stmt = conn.prepareStatement("SELECT * FROM chat_sessions WHERE id = ?")
    stmt.setString(1, id)
    val rs = stmt.executeQuery()
    try if (rs.next()) Some(fromRow(rs)) else None
    finally { rs.close(); stmt.close() }
  }

  def touchLastActive(conn: Connection, id: String): Unit = {
    val stmt = conn.prepareStatement("UPDATE chat_sessions SET last_active_at = now() WHERE id = ?")
    stmt.setString(1, id)
    stmt.executeUpdate()
    stmt.close()
  }
}
