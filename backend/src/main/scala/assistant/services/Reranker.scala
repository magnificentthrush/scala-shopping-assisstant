package assistant.services

import assistant.domain.{Filters, Product}

/** Turns the 30 candidates `ProductProvider.search` returns into the top 5
  * actually shown to the user.
  *
  * project-plan.md is explicit that the MVP reranker should be
  * "deterministic and local so demos don't depend on an extra LLM call" —
  * this is plain Scala scoring, not a Gemma call. It combines:
  *
  *   - keyword/attribute match: does the product's name/description/specs
  *     mention the words the user asked for?
  *   - price proximity: when a budget is given, products priced closer to
  *     (but not over) the budget rank slightly higher than ones far under
  *     it — a $118 pick for a $120 budget is usually a better answer than
  *     a $12 one, even though both satisfy "under $120".
  *
  * Pure function, no I/O — trivially unit-testable in isolation from any
  * database.
  */
object Reranker {

  def rerank(candidates: List[Product], filters: Filters, topN: Int = 5): List[Product] =
    candidates
      .map(p => p -> score(p, filters))
      .sortBy { case (_, s) => -s }
      .take(topN)
      .map(_._1)

  private def score(product: Product, filters: Filters): Double = {
    val haystack = List(
      Some(product.name),
      product.brand,
      product.category,
      product.description,
      product.productSpecifications
    ).flatten.mkString(" ").toLowerCase

    val keywordScore = filters.keywords.count(k => haystack.contains(k.toLowerCase)).toDouble * 2.0

    val attributeScore = filters.attributes.values.count(v => haystack.contains(v.toLowerCase)).toDouble * 1.5

    val categoryScore = filters.category
      .flatMap(c => product.category.map(pc => if (pc.equalsIgnoreCase(c)) 3.0 else 0.0))
      .getOrElse(0.0)

    val priceScore = (filters.budget, product.price) match {
      case (Some(budget), Some(price)) if price <= budget && budget > 0 =>
        // Closer to budget (but not over) scores higher; ranges 0..1.
        price / budget
      case _ => 0.0
    }

    keywordScore + attributeScore + categoryScore + priceScore
  }
}
