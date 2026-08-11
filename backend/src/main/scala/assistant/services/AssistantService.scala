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
    messages: MessageRepo
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

    val filtersJsonStr = write(filters)
    val filtersJsonVal = ujson.read(filtersJsonStr)

    val msgRow = messages.insertAssistantMessage(conversationId, honestResult.assistantResponse, filtersJsonVal)

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
      reply = honestResult.assistantResponse,
      followUpQuestion = honestResult.followUpQuestion,
      products = products,
      assistantMessage = assistantMsgResponse
    )
  }
}
