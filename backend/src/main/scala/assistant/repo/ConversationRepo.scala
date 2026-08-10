package assistant.repo

import assistant.db.Database
import assistant.domain.Conversation
import java.sql.{Connection, ResultSet}

class ConversationRepo(db: Database) {

  private def fromRow(rs: ResultSet): Conversation = Conversation(
    id = rs.getString("id"),
    userId = rs.getString("user_id"),
    title = Option(rs.getString("title")),
    createdAt = rs.getTimestamp("created_at").toInstant,
    updatedAt = rs.getTimestamp("updated_at").toInstant,
    lastMessageAt = rs.getTimestamp("last_message_at").toInstant
  )

  /** Creates a new conversation row for `userId`. Called as part of
    * "start new chat" (`POST /api/conversations`), always inside the same
    * transaction as the matching `chat_sessions` insert — see
    * `ConversationService.startNewChat`.
    */
  def insert(conn: Connection, userId: String): Conversation = {
    val stmt = conn.prepareStatement(
      "INSERT INTO conversations (user_id) VALUES (?) RETURNING *"
    )
    stmt.setString(1, userId)
    val rs = stmt.executeQuery()
    try { rs.next(); fromRow(rs) }
    finally { rs.close(); stmt.close() }
  }

  def findById(id: String): Option[Conversation] = db.withConnection { conn =>
    val stmt = conn.prepareStatement("SELECT * FROM conversations WHERE id = ?")
    stmt.setString(1, id)
    val rs = stmt.executeQuery()
    try if (rs.next()) Some(fromRow(rs)) else None
    finally { rs.close(); stmt.close() }
  }

  /** Ordered by `last_message_at DESC` per API_CONTRACT.md — newest
    * activity first, using `conversations_last_message_at_idx`.
    */
  def listForUser(userId: String): List[Conversation] = db.withConnection { conn =>
    val stmt = conn.prepareStatement(
      "SELECT * FROM conversations WHERE user_id = ? ORDER BY last_message_at DESC"
    )
    stmt.setString(1, userId)
    val rs = stmt.executeQuery()
    try Iterator.continually(rs).takeWhile(_.next()).map(fromRow).toList
    finally { rs.close(); stmt.close() }
  }

  /** Rename. The `user_id` check in the WHERE clause IS the authorization
    * check (ARCHITECTURE.md §3/§4) — not optional decoration. Returns
    * `None` if no row matched, which means either the conversation does
    * not exist or it belongs to someone else; the service layer is
    * responsible for turning that into 404 vs 403 as appropriate (we
    * intentionally do not leak "it exists but isn't yours" at the SQL
    * layer to avoid enumeration).
    */
  def renameOwnedBy(id: String, userId: String, title: String): Option[Conversation] = db.withConnection { conn =>
    val stmt = conn.prepareStatement(
      """UPDATE conversations
        |SET title = ?, updated_at = now()
        |WHERE id = ? AND user_id = ?
        |RETURNING *""".stripMargin
    )
    stmt.setString(1, title)
    stmt.setString(2, id)
    stmt.setString(3, userId)
    val rs = stmt.executeQuery()
    try if (rs.next()) Some(fromRow(rs)) else None
    finally { rs.close(); stmt.close() }
  }

  /** Hard delete, ownership-checked in the WHERE clause. `ON DELETE CASCADE`
    * on messages/conversation_state/chat_sessions does the rest at the DB
    * level — we don't need to delete those rows manually.
    * Returns true if a row was actually deleted.
    */
  def deleteOwnedBy(id: String, userId: String): Boolean = db.withConnection { conn =>
    val stmt = conn.prepareStatement("DELETE FROM conversations WHERE id = ? AND user_id = ?")
    stmt.setString(1, id)
    stmt.setString(2, userId)
    val affected = stmt.executeUpdate()
    stmt.close()
    affected > 0
  }

  /** Bumps `last_message_at`/`updated_at` after a new turn is saved. Called
    * from within the same transaction that appends to `messages`.
    */
  def touchLastMessageAt(conn: Connection, id: String): Unit = {
    val stmt = conn.prepareStatement(
      "UPDATE conversations SET last_message_at = now(), updated_at = now() WHERE id = ?"
    )
    stmt.setString(1, id)
    stmt.executeUpdate()
    stmt.close()
  }
}
