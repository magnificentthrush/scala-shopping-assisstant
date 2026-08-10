package assistant.services

import assistant.db.Database
import assistant.domain.{AppError, ChatSession, Conversation, Message}
import assistant.repo.{ChatSessionRepo, ConversationRepo, ConversationStateRepo, MessageRepo}

final case class NewChatResult(conversation: Conversation, session: ChatSession)
final case class ResumeResult(conversation: Conversation, session: ChatSession, messages: List[Message])

/** Business logic behind the "six user-facing actions" table in
  * ARCHITECTURE.md §4: start, send (that one lives in `ChatService`,
  * because its pipeline is large enough to deserve its own file), list,
  * resume, delete, rename.
  *
  * Every method that touches an existing conversation takes `userId` and
  * checks ownership as part of the same query (see `ConversationRepo`) —
  * "the WHERE clause IS the authorization check, not optional decoration."
  */
class ConversationService(
    db: Database,
    conversationRepo: ConversationRepo,
    chatSessionRepo: ChatSessionRepo,
    conversationStateRepo: ConversationStateRepo,
    messageRepo: MessageRepo
) {

  /** `POST /api/conversations`. Creates a `conversations` row, a matching
    * `conversation_state` row (empty filters), and a `chat_sessions` row —
    * all in one transaction, so a crash mid-way never leaves an orphaned
    * conversation with no session or no state row.
    */
  def startNewChat(userId: String): NewChatResult = db.withTransaction { conn =>
    val conversation = conversationRepo.insert(conn, userId)
    conversationStateRepo.initialize(conn, conversation.id)
    val session = chatSessionRepo.insert(conn, conversation.id, userId)
    NewChatResult(conversation, session)
  }

  /** `GET /api/conversations`. Scoped to the authenticated user, ordered
    * newest-activity-first.
    */
  def listForUser(userId: String): List[Conversation] = conversationRepo.listForUser(userId)

  /** `POST /api/conversations/{id}/resume`. Mints a NEW `chat_sessions` row
    * against the SAME conversation; history and filters are untouched.
    * Ownership is checked explicitly here (not just via the SQL WHERE
    * clause, since we also need to return the full message list) so we can
    * distinguish 404 (doesn't exist) from 403 (exists, not yours).
    */
  def resume(conversationId: String, userId: String): Either[AppError, ResumeResult] =
    conversationRepo.findById(conversationId) match {
      case None => Left(AppError.NotFound("Conversation does not exist"))
      case Some(conv) if conv.userId != userId =>
        Left(AppError.Forbidden("You do not have access to this conversation"))
      case Some(conv) =>
        val session = db.withTransaction(conn => chatSessionRepo.insert(conn, conv.id, userId))
        val messages = messageRepo.listForConversation(conv.id)
        Right(ResumeResult(conv, session, messages))
    }

  /** `PATCH /api/conversations/{id}` — rename. */
  def rename(conversationId: String, userId: String, title: String): Either[AppError, Conversation] = {
    if (title.trim.isEmpty) return Left(AppError.BadRequest("Title must not be empty"))
    conversationRepo.renameOwnedBy(conversationId, userId, title.trim) match {
      case Some(conv) => Right(conv)
      case None =>
        // Distinguish "doesn't exist" from "not yours" for a clean error,
        // without letting the rename statement itself leak that difference.
        conversationRepo.findById(conversationId) match {
          case None    => Left(AppError.NotFound("Conversation does not exist"))
          case Some(_) => Left(AppError.Forbidden("You do not have access to this conversation"))
        }
    }
  }

  /** `DELETE /api/conversations/{id}` — hard delete, cascades via FK. */
  def delete(conversationId: String, userId: String): Either[AppError, Unit] = {
    if (conversationRepo.deleteOwnedBy(conversationId, userId)) Right(())
    else
      conversationRepo.findById(conversationId) match {
        case None    => Left(AppError.NotFound("Conversation does not exist"))
        case Some(_) => Left(AppError.Forbidden("You do not have access to this conversation"))
      }
  }

  /** Resolves a `sessionId` to its owning conversation + verifies the
    * caller owns it. Used by `ChatService` at the very start of the
    * message pipeline (ARCHITECTURE.md §8 step 4) — "before touching
    * anything else."
    */
  def authorizeSession(sessionId: String, userId: String, chatSessionRepo: ChatSessionRepo): Either[AppError, ChatSession] =
    chatSessionRepo.findById(sessionId) match {
      case None => Left(AppError.NotFound("Session does not exist", "SESSION_NOT_FOUND"))
      case Some(session) if session.userId != userId =>
        Left(AppError.Forbidden("This session does not belong to you"))
      case Some(session) => Right(session)
    }
}
