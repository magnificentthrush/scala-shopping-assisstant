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
      case Failure(_) =>
        return Left(ValidationFailure(500, "Assistant failed to generate a response.", Some("ASSISTANT_FAILED")))
    }

    // Product retrieval + atomic RPC commit. Failure here -> 503 UPSTREAM_UNAVAILABLE
    Try(persistAndSearch(conversationId, llmResult)) match {
      case Success(result) => Right(result)
      case Failure(_) =>
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

    val filtersJsonStr = write(filters)
    val filtersJsonVal = ujson.read(filtersJsonStr)

    val msgRow = messages.insertAssistantMessage(conversationId, llmResult.assistantResponse, filtersJsonVal)

    val assistantMsgResponse = MessageResponse(
      id = msgRow.id,
      role = msgRow.role,
      content = msgRow.content,
      sequenceNumber = msgRow.sequenceNumber,
      createdAt = msgRow.createdAt,
      products = Seq.empty
    )

    AssistantTurnResult(
      mode = llmResult.mode,
      reply = llmResult.assistantResponse,
      followUpQuestion = llmResult.followUpQuestion,
      products = products,
      assistantMessage = assistantMsgResponse
    )
  }
}
