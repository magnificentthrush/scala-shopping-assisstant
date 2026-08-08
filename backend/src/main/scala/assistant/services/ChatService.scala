package assistant.services

import assistant.db.Database
import assistant.domain._
import assistant.logging.{Logger, LlmLogger}
import assistant.repo.{ChatSessionRepo, ConversationRepo, ConversationStateRepo, MessageRepo}
import assistant.services.llm.LLMClient
import assistant.services.providers.ProductProvider

final case class ChatTurnResult(
    sessionId: String,
    conversationId: String,
    mode: String, // "recommend" | "clarify" | "info" | "other"
    reply: String,
    followUpQuestion: Option[String],
    products: List[Product],
    userMessage: Message,
    assistantMessage: Message
)

/** Orchestrates `POST /api/sessions/{sessionId}/messages` — the single most
  * important piece of business logic in the app. This class exists
  * specifically so that this ordering lives in ONE place instead of being
  * reconstructed (and possibly gotten subtly wrong) inside an HTTP route
  * handler. It implements ARCHITECTURE.md §6 and §8 exactly, step for step:
  *
  *  1. Resolve `sessionId -> conversation_id`, check ownership.
  *  2. Regex pre-filter. Reject-on-match, 0 LLM calls, nothing persisted.
  *  3. Load `conversation_state.filters` + last ~6-10 messages.
  *  4. Gemma Call #1 (validation). Fail closed on unsafe / error / timeout.
  *  5. ONLY NOW: append the user's message.
  *  6. Gemma Call #2 (assistant). On failure: generic error, nothing more
  *     persisted (the user's message from step 5 stays — Call #2 failing
  *     does not un-happen step 5).
  *  7. `ProductProvider.search` -> top 30 -> `Reranker.rerank` -> top 5.
  *  8. Append the assistant's reply; update `conversation_state.filters`;
  *     bump `conversations.last_message_at`.
  */
class ChatService(
    db: Database,
    chatSessionRepo: ChatSessionRepo,
    conversationRepo: ConversationRepo,
    conversationStateRepo: ConversationStateRepo,
    messageRepo: MessageRepo,
    llmClient: LLMClient,
    productProvider: ProductProvider,
    llmLoggingEnabled: Boolean,
    recentMessageWindow: Int = 8
) {

  def sendMessage(sessionId: String, userId: String, rawMessage: String): Either[AppError, ChatTurnResult] = {
    val trimmed = rawMessage.trim
    if (trimmed.isEmpty) return Left(AppError.BadRequest("Message must not be empty"))

    // Step 1: resolve session -> conversation, check ownership BEFORE
    // touching anything else (ARCHITECTURE.md §3, §8 step 4).
    val session = chatSessionRepo.findById(sessionId) match {
      case None => return Left(AppError.NotFound("Session does not exist", "SESSION_NOT_FOUND"))
      case Some(s) if s.userId != userId =>
        Logger.security(s"IDOR attempt: userId=$userId tried to use sessionId=$sessionId owned by ${s.userId}")
        return Left(AppError.Forbidden("This session does not belong to you"))
      case Some(s) => s
    }
    val conversationId = session.conversationId

    // Step 2: regex pre-filter. Reject-on-match, never strip-and-continue.
    SecurityFilter.check(trimmed) match {
      case SecurityFilter.Reject(reason) =>
        Logger.security(s"Regex pre-filter rejected message: sessionId=$sessionId reason=$reason")
        return Left(AppError.Rejected())
      case SecurityFilter.Pass => // continue
    }

    // Step 3: load current filters (point lookup) + bounded recent history.
    val filtersBeforeTurn = conversationStateRepo.get(conversationId)
    val recentMessages = messageRepo.recentForConversation(conversationId, recentMessageWindow)
    val recentContext = formatContext(recentMessages)

    // Step 4: Call #1, validation. Fail-closed on any error/timeout/unsafe.
    val validation = llmClient.validate(trimmed, recentContext)
    validation match {
      case Right(v) =>
        LlmLogger.logValidationCall(
          llmLoggingEnabled,
          sessionId,
          userId,
          trimmed,
          filtersToJson(filtersBeforeTurn),
          v.safe,
          v.reason,
          v.latencyMs,
          v.inputTokens,
          v.outputTokens
        )
      case Left(_) => // Transport/parse failure — nothing to log with real latency/token figures.
    }

    val isSafe = validation match {
      case Right(v) => v.safe
      case Left(err) =>
        Logger.error(s"Gemma validation call failed for sessionId=$sessionId: $err")
        false // fail closed — identical treatment to safe:false
    }
    if (!isSafe) {
      Logger.security(s"Gemma Call #1 rejected message: sessionId=$sessionId")
      return Left(AppError.Rejected())
    }

    // Step 5: ONLY NOW is the user's message durable.
    val userMsg = db.withTransaction { conn =>
      val msg = messageRepo.appendUserMessage(conn, conversationId, trimmed, filtersBeforeTurn)
      chatSessionRepo.touchLastActive(conn, sessionId)
      msg
    }

    // Step 6: Call #2, assistant. Independently hardened — not "safe by
    // inheritance" from Call #1.
    val assistResult = llmClient.assist(trimmed, recentContext, filtersBeforeTurn) match {
      case Right(r) =>
        LlmLogger.logAssistantCall(
          llmLoggingEnabled,
          sessionId,
          userId,
          filtersToJson(filtersBeforeTurn),
          filtersToJson(r.filters),
          r.assistantResponse,
          r.latencyMs,
          r.inputTokens,
          r.outputTokens
        )
        r
      case Left(err) =>
        Logger.error(s"Gemma assistant call failed for sessionId=$sessionId: $err")
        return Left(AppError.AssistantFailed())
    }

    // Step 7: retrieval. Gemma never writes SQL — it only produced
    // structured filters; ProductProvider does the actual query.
    val candidates = productProvider.search(assistResult.filters)
    val topProducts = Reranker.rerank(candidates, assistResult.filters, topN = 5)

    // Step 8: persist the assistant's turn + update current filters +
    // bump conversation activity, all together.
    val assistantMsg = db.withTransaction { conn =>
      val msg = messageRepo.appendAssistantMessage(conn, conversationId, assistResult.assistantResponse, assistResult.filters)
      conversationStateRepo.update(conn, conversationId, assistResult.filters)
      conversationRepo.touchLastMessageAt(conn, conversationId)
      msg
    }

    val (mode, followUp) = classifyMode(topProducts, assistResult.assistantResponse)

    Right(
      ChatTurnResult(
        sessionId = sessionId,
        conversationId = conversationId,
        mode = mode,
        reply = assistResult.assistantResponse,
        followUpQuestion = followUp,
        products = topProducts,
        userMessage = userMsg,
        assistantMessage = assistantMsg
      )
    )
  }

  /** `recommend` when we actually have products to show; otherwise
    * `clarify` when the reply reads like a question (Gemma is asking for
    * more info), else `info`. This mirrors the `mode` table in
    * API_CONTRACT.md without requiring Gemma's JSON contract to include a
    * mode field of its own — mode is a deterministic function of what
    * retrieval actually found, which is more trustworthy than asking an
    * LLM to self-report it.
    */
  private def classifyMode(products: List[Product], reply: String): (String, Option[String]) =
    if (products.nonEmpty) ("recommend", None)
    else if (reply.trim.endsWith("?")) ("clarify", Some(reply.trim))
    else ("info", None)

  private def formatContext(messages: List[Message]): String =
    if (messages.isEmpty) "(no prior messages)"
    else messages.map(m => s"${m.role.value}: ${m.content}").mkString("\n")

  private def filtersToJson(filters: Filters): ujson.Value = {
    import assistant.domain.JsonCodecs._
    upickle.default.writeJs(filters)
  }
}
