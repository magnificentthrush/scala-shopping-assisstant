package assistant.services

import assistant.domain._
import assistant.domain.BigDecimalCodec.bigDecimalRW
import assistant.repo._
import upickle.default._

import scala.util.{Failure, Success, Try}

/** A no-match offer awaiting the user's confirmation, persisted inside the
  * `conversation_state.filters` envelope. On "accept" the stored filters are
  * re-searched deterministically; `candidateIds` records the rejected pool the
  * suggestion was drawn from.
  */
case class PendingOffer(filters: ExtractedFilters, suggestion: String, candidateIds: Seq[String])

object PendingOffer {
  implicit val rw: ReadWriter[PendingOffer] = macroRW
}

/** Orchestrates LLM Call #2 (docs/call2Plan.md §4) and Call #3 (relevance re-check).
  * Turns a validated and persisted user turn into a full assistant response:
  * - Loads current conversation state (filters + pending offer envelope) and recent history
  * - Runs Call #2 via AssistantPrompt
  * - Short-circuits accept/reject confirmations of a pending offer
  * - Performs product search + reranking + Call #3 re-check when mode == "recommend" and filters are present
  * - Commits the assistant message + state envelope atomically via commit_assistant_turn RPC
  */
class AssistantService(
    llmClient: LLMClient,
    productProvider: ProductProvider,
    conversationStates: ConversationStateRepo,
    messages: MessageRepo,
    conversations: ConversationRepo
) {

  /** Runs Call #2, optional catalog search, and commits the assistant turn. */
  def respond(conversationId: String, latestMessage: String): Either[ValidationFailure, AssistantTurnResult] = {
    val (currentFilters, pendingOffer) = conversationStates.find(conversationId) match {
      case Some(state) => readStateEnvelope(state.filters)
      case None        => (None, None)
    }
    val recentMessages = messages.recent(conversationId, 8)

    val pendingContext = pendingOffer.map(p =>
      PendingOfferContext(suggestion = p.suggestion, filtersSummary = filterSummary(p.filters))
    )

    // Call #1 is LLM safety; Call #2 is assistant prompt. Failure here -> 500 ASSISTANT_FAILED
    val llmResult =
      Try(AssistantPrompt.respond(latestMessage, currentFilters, recentMessages, llmClient, pendingContext)) match {
        case Success(res) => res
        case Failure(ex) =>
          System.err.println(s"[AssistantService] Call #2 LLM failed: ${ex.getMessage}")
          ex.printStackTrace()
          return Left(ValidationFailure(500, "Assistant failed to generate a response.", Some("ASSISTANT_FAILED")))
      }

    // Currency guard: the catalog is INR-only, but users sometimes state a
    // budget in dollars. Deterministically convert plausible amounts or force
    // a clarify turn for implausible ones (e.g. "$1000 shirt" is almost
    // certainly a mistyped ₹1000) rather than trusting the LLM's own
    // conversion/sanity-check, which hallucinates figures under load.
    val adjustedResult = CurrencyGuard.resolve(latestMessage, llmResult.filters.category) match {
      case CurrencyGuard.Convert(inr) =>
        llmResult.copy(filters = llmResult.filters.copy(budget = Some(inr)))
      case CurrencyGuard.Clarify(usd, inr) =>
        val usdFormatted = formatInr(usd)
        llmResult.copy(
          mode = "clarify",
          filters = llmResult.filters.copy(budget = None),
          assistantResponse =
            s"Heads up — our prices are in Indian Rupees (₹), not US Dollars. $$$usdFormatted would be about " +
              s"₹${formatInr(inr)} — that's unusually high for this category.",
          followUpQuestion = Some(s"Did you mean ₹$usdFormatted instead?")
        )
      case CurrencyGuard.NoDollarAmount => llmResult
    }

    // Product retrieval + atomic RPC commit. Failure here -> 503 UPSTREAM_UNAVAILABLE
    Try(persistAndSearch(conversationId, adjustedResult, pendingOffer, latestMessage)) match {
      case Success(result) => Right(result)
      case Failure(ex) =>
        System.err.println(s"[AssistantService] Phase B persistAndSearch failed: ${ex.getMessage}")
        ex.printStackTrace()
        Left(ValidationFailure(503, "Upstream database or search service unavailable.", Some("UPSTREAM_UNAVAILABLE")))
    }
  }

  // Handles pending-offer replies, search + rerank + Call #3, then commits the turn.
  private def persistAndSearch(
      conversationId: String,
      llmResult: AssistantLLMResult,
      pendingOffer: Option[PendingOffer],
      latestMessage: String
  ): AssistantTurnResult = {
    val filters = llmResult.filters

    // Confirmation short-circuit: the user is answering a pending no-match
    // offer, so re-run the stored search deterministically (no Call #3 — the
    // user explicitly asked for these items) and clear the offer. Accept/
    // reject without a pending offer is ignored and falls through to the
    // normal path.
    llmResult.pendingAction match {
      case Some("accept") if pendingOffer.isDefined =>
        val pending = pendingOffer.get
        val candidates = productProvider.search(pending.filters, limit = 30)
        val products = Reranker.rerank(candidates, pending.filters, limit = 5).products
        return commitTurn(
          conversationId,
          reply = "Here are those items — hope one of them works for you.",
          followUpQuestion = None,
          mode = llmResult.mode,
          filters = filters,
          pending = None,
          products = products,
          showFilterSummary = false
        )
      case Some("reject") if pendingOffer.isDefined =>
        return commitTurn(
          conversationId,
          reply = "No problem — let me know what else you'd like to find.",
          followUpQuestion = None,
          mode = llmResult.mode,
          filters = filters,
          pending = None,
          products = Seq.empty,
          showFilterSummary = false
        )
      case _ => ()
    }

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

    val reranked =
      if (shouldSearch) Reranker.rerank(productProvider.search(filters, limit = 30), filters, limit = 5).products
      else Seq.empty[Product]

    if (!shouldSearch) {
      return commitTurn(
        conversationId,
        reply = gatedResult.assistantResponse,
        followUpQuestion = gatedResult.followUpQuestion,
        mode = gatedResult.mode,
        filters = filters,
        pending = None,
        products = Seq.empty,
        showFilterSummary = false
      )
    }

    // The LLM wrote its reply before search ran, so it can't know how the
    // catalog actually responded — "here are some great options" above zero
    // product cards is dishonest. With no candidates there is nothing for the
    // re-check to judge, so keep the deterministic zero-result message.
    if (reranked.isEmpty) {
      return commitTurn(
        conversationId,
        reply =
          "I couldn't find anything in our catalog matching those exact filters. " +
            "Try broadening the category or raising the budget and I'll search again.",
        followUpQuestion = Some("Would you like to relax the budget or browse a wider category?"),
        mode = gatedResult.mode,
        filters = filters,
        pending = None,
        products = Seq.empty,
        showFilterSummary = true
      )
    }

    // Call #3 — LLM relevance re-check of the reranked candidates. Fails OPEN:
    // a broken re-check (timeout/parse) must never block product display, so a
    // throw is logged and treated as a full match.
    Try(RelevanceCheck.check(latestMessage, Some(filters), reranked, llmClient)) match {
      case Success(verdict) if verdict.verdict == "match" =>
        val matchedIds = verdict.matchedIds.toSet
        val matched = reranked.filter(p => matchedIds.contains(p.id))
        commitTurn(
          conversationId,
          reply = gatedResult.assistantResponse,
          followUpQuestion = gatedResult.followUpQuestion,
          mode = gatedResult.mode,
          filters = filters,
          pending = None,
          products = if (matched.nonEmpty) matched else reranked,
          showFilterSummary = true
        )
      case Success(verdict) =>
        val suggestion = verdict.suggestion
          .getOrElse(filters.category.map(c => s"similar $c items").getOrElse("similar items"))
        commitTurn(
          conversationId,
          reply =
            s"We don't have that exact product in our catalogue, but if you're looking for " +
              s"$suggestion, I can show you those — want me to check?",
          followUpQuestion = None,
          mode = gatedResult.mode,
          filters = filters,
          pending = Some(PendingOffer(filters, suggestion, reranked.map(_.id))),
          products = Seq.empty,
          showFilterSummary = false
        )
      case Failure(ex) =>
        System.err.println(s"[AssistantService] Call #3 relevance re-check failed, failing open: ${ex.getMessage}")
        commitTurn(
          conversationId,
          reply = gatedResult.assistantResponse,
          followUpQuestion = gatedResult.followUpQuestion,
          mode = gatedResult.mode,
          filters = filters,
          pending = None,
          products = reranked,
          showFilterSummary = true
        )
    }
  }

  /** Persists the assistant message + state envelope and builds the turn
    * result — the shared tail of every persistAndSearch path.
    */
  private def commitTurn(
      conversationId: String,
      reply: String,
      followUpQuestion: Option[String],
      mode: String,
      filters: ExtractedFilters,
      pending: Option[PendingOffer],
      products: Seq[Product],
      showFilterSummary: Boolean
  ): AssistantTurnResult = {
    // The UI renders a single message per turn — fold the separate
    // followUpQuestion into the same text instead of leaving it as an
    // unmerged field the frontend has to render as its own clickable block.
    // The prompt is instructed to never duplicate a question across both
    // fields, but this guard also protects against a misbehaving LLM.
    val combinedText = followUpQuestion match {
      case Some(question) if !reply.trim.endsWith("?") => s"${reply.trim} $question"
      case _                                           => reply
    }

    // The resolved filters are appended as a deterministic summary — the LLM's
    // own prose hallucinates budget numbers (it wrote "₹12,000" for a 2000
    // budget), so per ARCHITECTURE.md §5 the authoritative summary is built
    // here, and the prompt is instructed never to quote figures itself.
    val parts = filterSummaryParts(filters)
    val finalReply =
      if (showFilterSummary && parts.nonEmpty) s"$combinedText (Filters: ${parts.mkString(", ")})"
      else combinedText

    val envelope = ujson.Obj(
      "filters" -> writeJs(filters),
      "pending" -> pending.map(writeJs(_)).getOrElse(ujson.Null)
    )

    val msgRow = messages.insertAssistantMessage(conversationId, finalReply, envelope, products)

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
      products = products
    )

    AssistantTurnResult(
      mode = mode,
      reply = finalReply,
      followUpQuestion = followUpQuestion,
      products = products,
      assistantMessage = assistantMsgResponse
    )
  }

  /** Reads the `conversation_state.filters` blob as an envelope
    * `{ "filters": ..., "pending": ... | null }`. Blobs written before the
    * envelope existed are a legacy bare `ExtractedFilters` object (no
    * "filters" key) and read with no pending offer. A corrupt blob degrades
    * to (no filters, no pending) rather than failing the turn.
    */
  private def readStateEnvelope(blob: ujson.Value): (Option[ExtractedFilters], Option[PendingOffer]) =
    Try {
      blob match {
        case ujson.Obj(map) if map.contains("filters") =>
          val filters = Try(read[ExtractedFilters](map("filters").render())).toOption
          val pending = map.get("pending") match {
            case Some(ujson.Null) | None => None
            case Some(p)                 => Try(read[PendingOffer](p.render())).toOption
          }
          (filters, pending)
        case legacy =>
          (Try(read[ExtractedFilters](legacy.render())).toOption, None)
      }
    }.getOrElse((None, None))

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

  // Fills a sidebar title from filters only when the conversation is still untitled.
  private def setConversationTitleIfUntitled(conversationId: String, filters: ExtractedFilters): Unit =
    deriveTitle(filters).foreach { title =>
      conversations.setTitleIfNull(conversationId, title)
    }

  // Category, budget, and first keyword — the bits shown in the appended filter summary.
  private def filterSummaryParts(filters: ExtractedFilters): Seq[String] =
    filters.category.toSeq ++
      filters.budget.map(b => s"under ₹${formatInr(b)}") ++
      filters.keywords.headOption.map(k => s""""$k"""").toSeq

  // Comma-joined filter summary used when describing a pending no-match offer.
  private def filterSummary(filters: ExtractedFilters): String =
    filterSummaryParts(filters).mkString(", ")

  // Formats a rupee amount with Indian grouping (e.g. 9960 → "9,960").
  private def formatInr(amount: BigDecimal): String = {
    val fmt = java.text.NumberFormat.getNumberInstance(new java.util.Locale("en", "IN"))
    fmt.setMaximumFractionDigits(0)
    fmt.format(amount.bigDecimal)
  }
}
