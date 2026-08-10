package assistant.repo

import assistant.db.{Database, Rs}
import assistant.domain.{Filters, Message, Role}
import assistant.domain.JsonCodecs._
import java.sql.{Connection, ResultSet}
import org.postgresql.util.PGobject

class MessageRepo(db: Database) {

  private def fromRow(rs: ResultSet): Message = Message(
    id = rs.getString("id"),
    conversationId = rs.getString("conversation_id"),
    sequenceNumber = rs.getInt("sequence_number"),
    role = Role.fromString(rs.getString("role")),
    content = rs.getString("content"),
    filtersSnapshot = Option(rs.getString("filters_snapshot")).map(s => upickle.default.read[Filters](s)),
    safe = Rs.optBoolean(rs, "safe"),
    createdAt = rs.getTimestamp("created_at").toInstant
  )

  private def jsonbParam(json: Option[String]): PGobject = {
    val obj = new PGobject()
    obj.setType("jsonb")
    obj.setValue(json.orNull)
    obj
  }

  /** Next `sequence_number` for a conversation. Turn order is explicit, per
    * database-schema.md, never inferred from `created_at` alone — two
    * messages saved within the same millisecond must still sort correctly.
    */
  private def nextSequenceNumber(conn: Connection, conversationId: String): Int = {
    val stmt = conn.prepareStatement(
      "SELECT COALESCE(MAX(sequence_number), 0) + 1 AS next FROM messages WHERE conversation_id = ?"
    )
    stmt.setString(1, conversationId)
    val rs = stmt.executeQuery()
    try { rs.next(); rs.getInt("next") }
    finally { rs.close(); stmt.close() }
  }

  /** Appends the USER's message. Per ARCHITECTURE.md §6 this must only ever
    * be called AFTER the regex pre-filter and Gemma Call #1 both pass —
    * callers (ChatService), not this repo, are responsible for that
    * ordering. `safe` is always `Some(true)` here because a rejected
    * message is never persisted at all.
    */
  def appendUserMessage(
      conn: Connection,
      conversationId: String,
      content: String,
      filtersSnapshot: Filters
  ): Message = {
    val seq = nextSequenceNumber(conn, conversationId)
    val stmt = conn.prepareStatement(
      """INSERT INTO messages (conversation_id, sequence_number, role, content, filters_snapshot, safe)
        |VALUES (?, ?, 'user', ?, ?, true)
        |RETURNING *""".stripMargin
    )
    stmt.setString(1, conversationId)
    stmt.setInt(2, seq)
    stmt.setString(3, content)
    stmt.setObject(4, jsonbParam(Some(upickle.default.write(filtersSnapshot))))
    val rs = stmt.executeQuery()
    try { rs.next(); fromRow(rs) }
    finally { rs.close(); stmt.close() }
  }

  /** Appends the ASSISTANT's reply. `safe` is always NULL for assistant
    * rows — the validation pipeline never runs on our own output
    * (database-schema.md).
    */
  def appendAssistantMessage(
      conn: Connection,
      conversationId: String,
      content: String,
      filtersSnapshot: Filters
  ): Message = {
    val seq = nextSequenceNumber(conn, conversationId)
    val stmt = conn.prepareStatement(
      """INSERT INTO messages (conversation_id, sequence_number, role, content, filters_snapshot, safe)
        |VALUES (?, ?, 'assistant', ?, ?, NULL)
        |RETURNING *""".stripMargin
    )
    stmt.setString(1, conversationId)
    stmt.setInt(2, seq)
    stmt.setString(3, content)
    stmt.setObject(4, jsonbParam(Some(upickle.default.write(filtersSnapshot))))
    val rs = stmt.executeQuery()
    try { rs.next(); fromRow(rs) }
    finally { rs.close(); stmt.close() }
  }

  /** Full history for a conversation, oldest first — used by
    * `POST /conversations/{id}/resume` to rebuild the frontend's message
    * list.
    */
  def listForConversation(conversationId: String): List[Message] = db.withConnection { conn =>
    val stmt = conn.prepareStatement(
      "SELECT * FROM messages WHERE conversation_id = ? ORDER BY sequence_number ASC"
    )
    stmt.setString(1, conversationId)
    val rs = stmt.executeQuery()
    try Iterator.continually(rs).takeWhile(_.next()).map(fromRow).toList
    finally { rs.close(); stmt.close() }
  }

  /** Last N messages, oldest-first, for bounding what gets sent to Gemma
    * (ARCHITECTURE.md §4: "~6-10 raw messages, not the full transcript").
    */
  def recentForConversation(conversationId: String, limit: Int): List[Message] = db.withConnection { conn =>
    val stmt = conn.prepareStatement(
      "SELECT * FROM messages WHERE conversation_id = ? ORDER BY sequence_number DESC LIMIT ?"
    )
    stmt.setString(1, conversationId)
    stmt.setInt(2, limit)
    val rs = stmt.executeQuery()
    try Iterator.continually(rs).takeWhile(_.next()).map(fromRow).toList.reverse
    finally { rs.close(); stmt.close() }
  }
}
