package assistant.services

import assistant.domain.{ExtractedFilters, Gender, Product, TextMatch}

/** Deterministic reranker for candidate products (docs/call2Plan.md §4 & §8).
  * Scores candidates based on keyword/attribute hits and price proximity to budget,
  * then cuts recall results (top 30 DB matches) to top precision recommendations (top 5).
  */
object Reranker {

  /** Result of a rerank pass. `isExactMatch = false` means no candidate in the
    * pool cleared the minimum keyword-relevance gate, so `products` are the
    * best-effort ranking of the *full* original pool instead — callers should
    * be upfront with the user that these are the closest options found, not
    * exact matches, rather than presenting them with full confidence.
    */
  case class RerankResult(products: Seq[Product], isExactMatch: Boolean)

  /** Reranks candidate products deterministically based on keyword/attribute hits
    * and price proximity relative to the budget in `filters`. Returns top `limit` products.
    */
  def rerank(
      candidates: Seq[Product],
      filters: ExtractedFilters,
      limit: Int = 5
  ): RerankResult = {
    if (candidates.isEmpty) return RerankResult(Seq.empty, isExactMatch = true)

    val targetTerms = (filters.keywords ++ filters.attributes.values)
      .map(_.toLowerCase.trim)
      .filter(_.nonEmpty)

    val budgetOpt = filters.budget

    val requiredGender = Gender.requiredFrom(filters)

    // Concatenated product fields the keyword/gender gates scan.
    def searchableText(product: Product): String =
      Seq(
        product.name,
        product.brand.getOrElse(""),
        product.category,
        product.description.getOrElse(""),
        product.productSpecifications.getOrElse("")
      ).mkString(" ").toLowerCase

    // Hard gender gate: when the shopper told us men or women, products
    // word-matching the opposite audience ("women", "ladies", "girl", ...)
    // are removed outright — substring matching used to let "women" score as
    // "men", which is exactly how women's tops surfaced for a men query. If
    // exclusion empties the pool we return nothing rather than falling back
    // to opposite-gender rows; the caller's honest-message path explains it.
    val genderedPool = requiredGender match {
      case Some(g) =>
        val opposite = Gender.oppositeTerms(g)
        val kept = candidates.filterNot { p =>
          val text = searchableText(p)
          opposite.exists(t => TextMatch.containsTerm(text, t))
        }
        if (kept.isEmpty) return RerankResult(Seq.empty, isExactMatch = false)
        kept
      case None => candidates
    }

    // How many extracted filter terms appear in the product's searchable text.
    def keywordHitsOf(product: Product): Int =
      targetTerms.count(term => TextMatch.containsTerm(searchableText(product), term))

    // Relevance gate (docs/retrievalPlan.md §3 previously scoped this check to
    // the provider's rung-3 websearch fallback only; it's applied here to every
    // candidate regardless of which retrieval rung produced it, since rung 2's
    // category-only fallback is just as likely to hand back an unrelated
    // product). Require overlap with 2+ of the extracted terms when there are
    // 2+, else 1 — so a single generic-noun hit (e.g. "shoes") can't alone
    // qualify an otherwise unrelated product (e.g. a football shoe surfacing
    // for a "running shoes" query).
    val minHits = if (targetTerms.size >= 2) 2 else if (targetTerms.nonEmpty) 1 else 0

    // When gender is known it is a mandatory term: a candidate matching the
    // style keywords but never saying "men" (e.g. an unlabeled shirt) does
    // not qualify on its own — it can only come back via the best-effort
    // fallback below, which is flagged isExactMatch=false.
    def genderOk(p: Product): Boolean =
      requiredGender.forall(g => TextMatch.containsTerm(searchableText(p), g))

    val relevant =
      if (minHits > 0 || requiredGender.nonEmpty)
        genderedPool.filter(p => genderOk(p) && keywordHitsOf(p) >= minHits)
      else genderedPool
    val (pool, isExactMatch) =
      if (relevant.nonEmpty) (relevant, true)
      else (genderedPool, false) // Nothing cleared the gate — fall back to the (gender-filtered) pool as a best-effort answer.

    // Combined keyword, price-vs-budget, and rating score used to order the pool.
    def scoreOf(product: Product): (Double, Double) = {
      val keywordHits = keywordHitsOf(product)

      val priceScore = budgetOpt match {
        case Some(budget) if budget > 0 =>
          if (product.price <= budget) {
            // Under budget: cheaper relative to budget scores higher (max 2.0 at price -> 0)
            1.0 + (1.0 - (product.price / budget).toDouble)
          } else {
            // Over budget: penalty proportional to excess over budget
            val excess = (product.price - budget).toDouble / budget.toDouble
            -2.0 * excess
          }
        case _ =>
          0.0
      }

      val ratingScore = product.rating.flatMap(r => scala.util.Try(r.trim.toDouble).toOption) match {
        case Some(rating) => 0.5 * rating
        case None         => 0.0
      }

      // Keyword relevance is weighted above rating/price so a strong textual
      // match to the user's specific intent isn't outranked by a popular but
      // only-loosely-related product that just happens to have a high rating.
      val totalScore = (keywordHits.toDouble * 2.0) + priceScore + ratingScore
      (totalScore, ratingScore)
    }

    val scored = pool.map(p => (p, scoreOf(p)))

    // Sort descending by totalScore; tie-break by rating desc, then price asc.
    // Dedupe by normalized name before taking the limit, keeping the highest-scored copy.
    val ranked = scored
      .sortBy { case (p, (totalScore, ratingScore)) => (-totalScore, -ratingScore, p.price) }
      .foldLeft((Set.empty[String], List.empty[Product])) { case ((seen, kept), (p, _)) =>
        val key = p.name.toLowerCase.trim
        if (seen.contains(key)) (seen, kept)
        else (seen + key, p :: kept)
      }
      ._2
      .reverse
      .take(limit)

    RerankResult(ranked, isExactMatch)
  }
}
