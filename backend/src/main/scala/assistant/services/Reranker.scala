package assistant.services

import assistant.domain.{ExtractedFilters, Product}

/** Deterministic reranker for candidate products (docs/call2Plan.md §4 & §8).
  * Scores candidates based on keyword/attribute hits and price proximity to budget,
  * then cuts recall results (top 30 DB matches) to top precision recommendations (top 5).
  */
object Reranker {

  /** Reranks candidate products deterministically based on keyword/attribute hits
    * and price proximity relative to the budget in `filters`. Returns top `limit` products.
    */
  def rerank(
      candidates: Seq[Product],
      filters: ExtractedFilters,
      limit: Int = 5
  ): Seq[Product] = {
    if (candidates.isEmpty) return Seq.empty

    val targetTerms = (filters.keywords ++ filters.attributes.values)
      .map(_.toLowerCase.trim)
      .filter(_.nonEmpty)

    val budgetOpt = filters.budget

    val scored = candidates.map { product =>
      val searchableText = Seq(
        product.name,
        product.brand.getOrElse(""),
        product.category,
        product.description.getOrElse(""),
        product.productSpecifications.getOrElse("")
      ).mkString(" ").toLowerCase

      // Count keyword/attribute term hits in text
      val keywordHits = targetTerms.count(term => searchableText.contains(term))

      // Price proximity score relative to budget
      val priceScore = budgetOpt match {
        case Some(budget) if budget > 0 =>
          if (product.price <= budget) {
            // Under budget: bonus scaled by price proximity to budget (up to +1.0)
            1.0 + (product.price / budget).toDouble
          } else {
            // Over budget: penalty proportional to excess over budget
            val excess = (product.price - budget).toDouble / budget.toDouble
            -2.0 * excess
          }
        case _ =>
          0.0
      }

      val totalScore = keywordHits.toDouble + priceScore
      (product, totalScore)
    }

    // Sort descending by totalScore, tie-break by price ascending
    scored
      .sortBy { case (p, score) => (-score, p.price) }
      .map(_._1)
      .take(limit)
  }
}
