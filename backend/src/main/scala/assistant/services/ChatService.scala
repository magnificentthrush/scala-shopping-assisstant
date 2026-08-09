package assistant.services

import assistant.db.Database
import assistant.domain._
import assistant.logging.{Logger, LlmLogger}
import assistant.repo.{ChatSessionRepo, ConversationRepo, ConversationStateRepo, MessageRepo}
import assistant.services.llm.{LLMClient => GemmaLLMClient}
import assistant.services.providers.ProductProvider

final case class ChatTurnResult(
    sessionId: String,
    conversationId: String,
    mode: String,
    reply: String,
    followUpQuestion: Option[String],
    products: List[Product],
    userMessage: Message,
    assistantMessage: Message
)

class ChatService(
    db: Database,
    chatSessionRepo: ChatSessionRepo,
    conversationRepo: ConversationRepo,
    conversationStateRepo: ConversationStateRepo,
    messageRepo: MessageRepo,
    llmClient: GemmaLLMClient,
    productProvider: ProductProvider,
    llmLoggingEnabled: Boolean,
    recentMessageWindow: Int = 8
) {

  def sendMessage(sessionId: String, userId: String, rawMessage: String): Either[AppError, ChatTurnResult] = {
    val trimmed = rawMessage.trim
    if (trimmed.isEmpty) return Left(AppError.BadRequest("Message must not be empty"))

    val session = chatSessionRepo.findById(sessionId) match {
      case None => return Left(AppError.NotFound("Session does not exist", "SESSION_NOT_FOUND"))
      case Some(s) if s.userId != userId =>
        Logger.security(s"IDOR attempt: userId=$userId tried to use sessionId=$sessionId owned by ${s.userId}")
        return Left(AppError.Forbidden("This session does not belong to you"))
      case Some(s) => s
    }
    val conversationId = session.conversationId

    SecurityFilter.check(trimmed) match {
      case SecurityFilter.Reject(reason) =>
        Logger.security(s"Regex pre-filter rejected message: sessionId=$sessionId reason=$reason")
        return Left(AppError.Rejected())
      case SecurityFilter.Pass =>
    }

    val filtersBeforeTurn = conversationStateRepo.get(conversationId)
    val recentMessages = messageRepo.recentForConversation(conversationId, recentMessageWindow)
    val recentContext = formatContext(recentMessages)

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
      case Left(_) =>
    }

    val isSafe = validation match {
      case Right(v) => v.safe
      case Left(err) =>
        Logger.error(s"Gemma validation call failed for sessionId=$sessionId: $err")
        false
    }
    if (!isSafe) {
      Logger.security(s"Gemma Call #1 rejected message: sessionId=$sessionId")
      return Left(AppError.Rejected())
    }

    val userMsg = db.withTransaction { conn =>
      val msg = messageRepo.appendUserMessage(conn, conversationId, trimmed, filtersBeforeTurn)
      chatSessionRepo.touchLastActive(conn, sessionId)
      msg
    }

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

    val candidates = productProvider.search(assistResult.filters)
    val topProducts = Reranker.rerank(candidates, assistResult.filters, topN = 5)

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