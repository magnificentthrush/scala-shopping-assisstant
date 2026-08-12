package assistant.services

import assistant.domain.{
  ChatSession,
  CommittedTurn,
  Conversation,
  ConversationsListResponse,
  ConversationSummary,
  MessageResponse,
  MessageRow,
  RenameConversationRequest,
  ResumeConversationResponse,
  StartConversationResponse,
  ValidationFailure
}
import assistant.repo.{ChatSessionRepo, ConversationRepo, ConversationStateRepo, MessageRepo}
import scala.util.{Failure, Success, Try}

/** Orchestrates the five conversation CRUD actions and `commitUserTurn`
  * (docs/conversationPlan.md §5, §7). Routes stay thin — they parse the HTTP
  * request, call one of these methods, and turn the `Either` into a status +
  * JSON body, exactly like `AuthService`.
  *
  * Ownership checks are mandatory, not best-effort: every path that hits a
  * conversation resolves it to its `user_id` and compares against the caller
  * *before* touching any other data (ARCHITECTURE.md §3). Dependencies are
  * constructor-injected so tests can swap in fakes.
  */
class ConversationService(
    chatSessions: ChatSessionRepo,
    conversations: ConversationRepo,
    conversationStates: ConversationStateRepo,
    messages: MessageRepo
) {
  /** `POST /api/conversations` — inserts a bare `chat_sessions` row. Never
    * fails; `conversationId` is `None` until the first accepted message
    * lazy-creates the `conversations` row (ARCHITECTURE.md §4).
    */
  def start(userId: String): StartConversationResponse = {
    val session = chatSessions.insert(userId)
    StartConversationResponse(
      conversationId = None,
      sessionId = session.id,
      title = None,
      messages = Seq.empty
    )
  }

  /** `GET /api/conversations` — sidebar history, newest activity first. */
  def list(userId: String): ConversationsListResponse =
    ConversationsListResponse(conversations.listByUser(userId).map(toSummary))

  /** `POST /api/conversations/{id}/resume` — new session handle pointing at
    * the conversation, plus full history. 404 if the conversation doesn't
    * exist; 403 if it exists but belongs to another user.
    */
  def resume(
      conversationId: String,
      userId: String
  ): Either[ValidationFailure, ResumeConversationResponse] =
    for {
      conversation <- requireOwned(conversationId, userId)
    } yield {
      val session = chatSessions.insert(userId, conversationId = Some(conversation.id))
      ResumeConversationResponse(
        conversationId = conversation.id,
        sessionId = session.id,
        title = conversation.title,
        messages = messages.listByConversation(conversation.id).map(toResponse)
      )
    }

  /** `PATCH /api/conversations/{id}` — rename. Same 404/403 pattern, then
    * update title (the repo's `WHERE id=eq AND user_id=eq` is the second
    * belt-and-suspenders check).
    *
    * Blank / whitespace-only titles are rejected with 400: renaming to ""
    * would silently erase the sidebar label, which is a UX bug — the frontend
    * shows "New chat" for null titles, so an empty string is worse than null.
    */
  def rename(
      conversationId: String,
      userId: String,
      req: RenameConversationRequest
  ): Either[ValidationFailure, ConversationSummary] =
    for {
      _ <- requireOwned(conversationId, userId)
      trimmedTitle <- Right(req.title.trim).filterOrElse(
        _.nonEmpty,
        ValidationFailure(400, "Title cannot be blank", Some("BLANK_TITLE"))
      )
      updated <- conversations
        .updateTitle(conversationId, userId, trimmedTitle)
        .toRight(notFound)
        .map(toSummary)
    } yield updated

  /** `DELETE /api/conversations/{id}` — hard delete; messages/state/sessions
    * cascade (ON DELETE CASCADE). Same 404/403 pattern.
    */
  def delete(conversationId: String, userId: String): Either[ValidationFailure, Unit] =
    for {
      _ <- requireOwned(conversationId, userId)
      _ <- if (conversations.delete(conversationId, userId)) Right(()) else Left(notFound)
    } yield ()

  /** `POST /api/sessions/{sessionId}/messages` — ownership + lazy-create +
    * persistence, called after `MessageValidationService` passes (Call #1).
    *
    * 1. No session row → `404 SESSION_NOT_FOUND`
    * 2. Session owned by someone else → `403 FORBIDDEN`
    * 3. Session not yet attached to a conversation → lazy-create
    *    (conversation + state, then point the session at it)
    * 4. Persist the user's message row (next sequence number)
    * 5. Touch activity timestamps
    */
  def commitUserTurn(
      sessionId: String,
      userId: String,
      message: String
  ): Either[ValidationFailure, CommittedTurn] =
    Try(commitUserTurnUnsafe(sessionId, userId, message)) match {
      case Success(result) => result
      case Failure(_)      => Left(dbError)
    }

  private def commitUserTurnUnsafe(
      sessionId: String,
      userId: String,
      message: String
  ): Either[ValidationFailure, CommittedTurn] =
    for {
      session <- chatSessions.findById(sessionId).toRight(sessionNotFound)
      _ <- if (session.userId == userId) Right(()) else Left(forbidden)
      conversationId <- Right(lazyCreateConversation(session, userId))
      row <- Right(messages.insertUserMessage(conversationId, message))
      _ <- Right(conversations.touchLastMessageAt(conversationId))
      _ <- Right(chatSessions.touchActivity(sessionId))
    } yield CommittedTurn(conversationId = conversationId, userMessage = toResponse(row))

  /** Returns the conversation iff it exists **and** is owned by `userId`.
    * Order matters per ARCHITECTURE.md §3: a genuine 404 (missing row) must
    * not read as 403.
    */
  private def requireOwned(
      conversationId: String,
      userId: String
  ): Either[ValidationFailure, Conversation] =
    conversations.findById(conversationId) match {
      case None                         => Left(notFound)
      case Some(c) if c.userId != userId => Left(forbidden)
      case Some(c)                       => Right(c)
    }

  /** Lazy-create step of `commitUserTurn`: if the session has no conversation
    * yet, create the `conversations` row, its `conversation_state` row, and
    * point the session at it. Check-then-insert by design; the race that
    * would need `FOR UPDATE` locking (filters read-modify-write) doesn't exist
    * until Call #2 (docs/conversationPlan.md §3).
    */
  private def lazyCreateConversation(session: ChatSession, userId: String): String =
    session.conversationId match {
      case Some(conversationId) => conversationId
      case None =>
        val conversation = conversations.insert(userId)
        conversationStates.insertEmpty(conversation.id)
        chatSessions.setConversationId(session.id, conversation.id)
        conversation.id
    }

  private def toSummary(c: Conversation): ConversationSummary =
    ConversationSummary(
      id = c.id,
      title = c.title,
      createdAt = c.createdAt,
      updatedAt = c.updatedAt,
      lastMessageAt = c.lastMessageAt
    )

  private def toResponse(m: MessageRow): MessageResponse =
    MessageResponse(
      id = m.id,
      role = m.role,
      content = m.content,
      sequenceNumber = m.sequenceNumber,
      createdAt = m.createdAt,
      products = m.products.getOrElse(Seq.empty)
    )

  private def dbError: ValidationFailure =
    ValidationFailure(503, "Something went wrong, please try again.", Some("UPSTREAM_UNAVAILABLE"))

  private def sessionNotFound: ValidationFailure =
    ValidationFailure(404, "Session not found", Some("SESSION_NOT_FOUND"))

  private def notFound: ValidationFailure =
    ValidationFailure(404, "Conversation not found", Some("NOT_FOUND"))

  private def forbidden: ValidationFailure =
    ValidationFailure(403, "Forbidden", Some("FORBIDDEN"))
}