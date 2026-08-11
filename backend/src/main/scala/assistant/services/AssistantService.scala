package assistant.services

import assistant.domain._
import assistant.domain.BigDecimalCodec.bigDecimalRW
import assistant.repo._
import upickle.default._

import scala.util.{Failure, Success, Try}

/** Orchestrates LLM Call #2 (docs/call2Plan.md §4).
  * Turns a validated and persisted user turn into a full assistant response:
  * - Loads current conversation state filters and recent history
  * - Runs Call #2 via AssistantPrompt
  * - Performs product search + reranking when mode == "recommend" and filters are present
  * - Commits the assistant message + merged filters atomically via commit_assistant_turn RPC
  */
class AssistantService(
    llmClient: LLMClient,
    productProvider: ProductProvider,
    conversationStates: ConversationStateRepo,
    messages: MessageRepo,
    conversations: ConversationRepo
) {

  def respond(conversationId: String, latestMessage: String): Either[ValidationFailure, AssistantTurnResult] = {
    val currentFilters = conversationStates.find(conversationId).flatMap { state =>
      Try(read[ExtractedFilters](state.filters.render())).toOption
    }
    val recentMessages = messages.recent(conversationId, 8)

    // Call #1 is LLM safety; Call #2 is assistant prompt. Failure here -> 500 ASSISTANT_FAILED
    val llmResult = Try(AssistantPrompt.respond(latestMessage, currentFilters, recentMessages, llmClient)) match {
      case Success(res) => res
      case Failure(ex) =>
        System.err.println(s"[AssistantService] Call #2 LLM failed: ${ex.getMessage}")
        ex.printStackTrace()
        return Left(ValidationFailure(500, "Assistant failed to generate a response.", Some("ASSISTANT_FAILED")))
    }

    // Product retrieval + atomic RPC commit. Failure here -> 503 UPSTREAM_UNAVAILABLE
    Try(persistAndSearch(conversationId, llmResult)) match {
      case Success(result) => Right(result)
      case Failure(ex) =>
        System.err.println(s"[AssistantService] Phase B persistAndSearch failed: ${ex.getMessage}")
        ex.printStackTrace()
        Left(ValidationFailure(503, "Upstream database or search service unavailable.", Some("UPSTREAM_UNAVAILABLE")))
    }
  }

  private def persistAndSearch(conversationId: String, llmResult: AssistantLLMResult): AssistantTurnResult = {
    val filters = llmResult.filters
    val shouldSearch = llmResult.mode == "recommend" &&
      (filters.category.nonEmpty || filters.budget.nonEmpty || filters.keywords.nonEmpty || filters.attributes.nonEmpty)

    val products = if (shouldSearch) {
      val candidates = productProvider.search(filters, limit = 30)
      Reranker.rerank(candidates, filters, limit = 5)
    } else {
      Seq.empty[Product]
    }

    // The LLM wrote its reply before search ran, so it can't know the catalog
    // came back empty — "here are some great options" above zero product cards
    // is dishonest. Override with a deterministic zero-result message instead.
    val honestResult = if (shouldSearch && products.isEmpty) {
      llmResult.copy(
        assistantResponse =
          "I couldn't find anything in our catalog matching those exact filters. " +
            "Try broadening the category or raising the budget and I'll search again.",
        followUpQuestion = Some("Would you like to relax the budget or browse a wider category?")
      )
    } else llmResult

    // The resolved filters are appended as a deterministic summary — the LLM's
    // own prose hallucinates budget numbers (it wrote "₹12,000" for a 2000
    // budget), so per ARCHITECTURE.md §5 the authoritative summary is built
    // here, and the prompt is instructed never to quote figures itself.
    val summaryParts =
      filters.category.toSeq ++
        filters.budget.map(b => s"under ₹${formatInr(b)}") ++
        filters.keywords.headOption.map(k => s""""$k"""").toSeq
    val finalReply =
      if (shouldSearch && summaryParts.nonEmpty)
        s"${honestResult.assistantResponse} (Filters: ${summaryParts.mkString(", ")})"
      else honestResult.assistantResponse

    val filtersJsonStr = write(filters)
    val filtersJsonVal = ujson.read(filtersJsonStr)

    val msgRow = messages.insertAssistantMessage(conversationId, finalReply, filtersJsonVal)

    // Option B auto-title: once a conversation has been successfully answered,
    // derive a short sidebar label from the resolved filters and fill it in —
    // but only if the conversation is still untitled (setTitleIfNull's
    // `title=is.null` guard means a user's manual rename always wins). This is
    // best-effort: a title failure must never fail the turn, so it's wrapped
    // and only logged.
    try setConversationTitleIfUntitled(conversationId, filters)
    catch { case ex: Exception => System.err.println(s"[AssistantService] auto-title failed: ${ex.getMessage}") }

    val assistantMsgResponse = MessageResponse(
      id = msgRow.id,
      role = msgRow.role,
      content = msgRow.content,
      sequenceNumber = msgRow.sequenceNumber,
      createdAt = msgRow.createdAt,
      products = Seq.empty
    )

    AssistantTurnResult(
      mode = honestResult.mode,
      reply = finalReply,
      followUpQuestion = honestResult.followUpQuestion,
      products = products,
      assistantMessage = assistantMsgResponse
    )
  }

  /** Build a short sidebar title from the resolved Call #2 filters, e.g.
    * "Hiking shoes · under ₹9,960". Returns `None` when there's nothing
    * meaningful to say (pure `clarify`/`info` turns with empty filters), so
    * the conversation stays untitled rather than getting a junk label.
    * Priority: category, else first keyword; budget appended when present.
    */
  private[services] def deriveTitle(filters: ExtractedFilters): Option[String] = {
    val base = filters.category.orElse(filters.keywords.headOption).map(_.trim).filter(_.nonEmpty)
    base.map { b =>
      val withBudget = filters.budget match {
        case Some(amount) => s"$b · under ₹${formatInr(amount)}"
        case None         => b
      }
      // Sidebar labels should stay short.
      if (withBudget.length <= 60) withBudget else withBudget.take(57).trim + "…"
    }
  }

  private def setConversationTitleIfUntitled(conversationId: String, filters: ExtractedFilters): Unit =
    deriveTitle(filters).foreach { title =>
      conversations.setTitleIfNull(conversationId, title)
    }

  private def formatInr(amount: BigDecimal): String = {
    val fmt = java.text.NumberFormat.getNumberInstance(new java.util.Locale("en", "IN"))
    fmt.setMaximumFractionDigits(0)
    fmt.format(amount.bigDecimal)
  }
}
