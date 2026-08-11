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

    // Discovery-first gate (mirrors the READINESS RULE in AssistantPrompt's
    // system prompt): even if the LLM says "recommend", we only search when
    // the conversation has actually collected enough criteria — category,
    // gender for gender-relevant categories, and one more signal. Otherwise
    // the turn is forced into clarify so products never leak out early.
    val gatedResult =
      if (llmResult.mode == "recommend" && !hasEnoughInfo(filters)) forceClarify(filters, llmResult)
      else llmResult

    val shouldSearch = gatedResult.mode == "recommend" &&
      (filters.category.nonEmpty || filters.budget.nonEmpty || filters.keywords.nonEmpty || filters.attributes.nonEmpty)

    val rerankResult = if (shouldSearch) {
      val candidates = productProvider.search(filters, limit = 30)
      Reranker.rerank(candidates, filters, limit = 5)
    } else {
      Reranker.RerankResult(Seq.empty[Product], isExactMatch = true)
    }
    val products = rerankResult.products

    // The LLM wrote its reply before search ran, so it can't know how the
    // catalog actually responded — "here are some great options" above zero
    // product cards is dishonest, and so is presenting a merely-closest match
    // (e.g. football shoes for a "running shoes" query) with full confidence.
    // Override with a deterministic, honest message in either case; try the
    // search first, but say so plainly when the catalog couldn't fully deliver.
    val honestResult =
      if (shouldSearch && products.isEmpty) {
        gatedResult.copy(
          assistantResponse =
            "I couldn't find anything in our catalog matching those exact filters. " +
              "Try broadening the category or raising the budget and I'll search again.",
          followUpQuestion = Some("Would you like to relax the budget or browse a wider category?")
        )
      } else if (shouldSearch && !rerankResult.isExactMatch) {
        gatedResult.copy(assistantResponse = closestMatchMessage(filters))
      } else gatedResult

    // The UI renders a single message per turn — fold the separate
    // followUpQuestion into the same text instead of leaving it as an
    // unmerged field the frontend has to render as its own clickable block.
    // The prompt is instructed to never duplicate a question across both
    // fields, but this guard also protects against a misbehaving LLM.
    val combinedText = honestResult.followUpQuestion match {
      case Some(question) if !honestResult.assistantResponse.trim.endsWith("?") =>
        s"${honestResult.assistantResponse.trim} $question"
      case _ => honestResult.assistantResponse
    }

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
        s"$combinedText (Filters: ${summaryParts.mkString(", ")})"
      else combinedText

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

  private val GenderRelevantCategories = Set(
    "Clothing", "Footwear", "Watches", "Bags, Wallets & Belts",
    "Sunglasses", "Eyewear", "Jewellery", "Beauty And Personal Care"
  )

  private val GenderTerms =
    Seq("men", "women", "male", "female", "ladies", "gents", "unisex", "men's", "women's")

  /** Gender counts as known if the LLM recorded it in attributes, or if a
    * gender word shows up anywhere in the extracted keywords/attribute values
    * (e.g. "men's watch" as a keyword) — defensive against the LLM forgetting
    * the structured attribute.
    */
  private def genderKnown(f: ExtractedFilters): Boolean = {
    val haystack = (f.keywords ++ f.attributes.values).map(_.toLowerCase)
    f.attributes.get("gender").exists(_.trim.nonEmpty) ||
    haystack.exists(t => GenderTerms.exists(t.contains))
  }

  /** The discovery-first readiness check: category known, gender known when
    * the category is gender-relevant, and at least one more signal (budget,
    * keyword, or a non-gender attribute).
    */
  private def hasEnoughInfo(f: ExtractedFilters): Boolean = {
    val genderOk = !f.category.exists(GenderRelevantCategories) || genderKnown(f)
    val extraSignal = f.budget.nonEmpty || f.keywords.nonEmpty ||
      f.attributes.exists { case (k, _) => k != "gender" }
    f.category.nonEmpty && genderOk && extraSignal
  }

  /** Forces a premature "recommend" turn back into clarify: no products are
    * searched or returned, and the reply deterministically asks for the next
    * missing piece — gender first, then one more signal (budget/brand/etc).
    */
  private def forceClarify(filters: ExtractedFilters, llmResult: AssistantLLMResult): AssistantLLMResult = {
    val categoryPart = filters.category.map(c => s" the right $c").getOrElse("")
    val needsGender = filters.category.exists(GenderRelevantCategories) && !genderKnown(filters)
    if (needsGender) {
      llmResult.copy(
        mode = "clarify",
        assistantResponse = s"Happy to help you find$categoryPart.",
        followUpQuestion = Some("Are you shopping for men or women?")
      )
    } else {
      llmResult.copy(
        mode = "clarify",
        assistantResponse = s"Happy to help you find$categoryPart.",
        followUpQuestion = Some("Do you have a budget, brand, color, or use-case in mind?")
      )
    }
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

  /** Builds the honest caveat shown when the reranker couldn't clear its
    * keyword-relevance gate (`RerankResult.isExactMatch = false`) but still
    * returned a best-effort set of products. Picks the most specific
    * extracted term (longest keyword/attribute value, mirroring the
    * provider's own "salient" term selection) so the caveat names what
    * actually couldn't be matched rather than a generic filler word.
    */
  private def closestMatchMessage(filters: ExtractedFilters): String = {
    val salient = (filters.keywords ++ filters.attributes.values)
      .map(_.trim)
      .filter(_.nonEmpty)
      .sortBy(-_.length)
      .headOption
    val categoryPart = filters.category.map(c => s" $c").getOrElse("")
    salient match {
      case Some(term) =>
        s"""I couldn't find an exact match for "$term" in our catalog, so here are the closest$categoryPart options I found instead."""
      case None =>
        s"I couldn't find an exact match for those filters in our catalog, so here are the closest$categoryPart options I found instead."
    }
  }

  private def formatInr(amount: BigDecimal): String = {
    val fmt = java.text.NumberFormat.getNumberInstance(new java.util.Locale("en", "IN"))
    fmt.setMaximumFractionDigits(0)
    fmt.format(amount.bigDecimal)
  }
}
