package assistant.domain

/** Shared term-matching helpers for retrieval and reranking.
  *
  * Plain `String.contains` is wrong for gender terms: "women".contains("men")
  * is true, so every women's product scored a hit on the term "men" and
  * cleared the relevance gate. Word-boundary matching keeps "men" matching
  * "men's" (apostrophe is a boundary) but never "women"/"female".
  */
object TextMatch {

  /** True when `term` appears in `text` delimited by non-letters (or string
    * edges) on both sides. Case-insensitive; multi-word terms match as a
    * literal phrase with boundaries at the ends.
    */
  def containsTerm(text: String, term: String): Boolean = {
    val t = term.trim
    if (t.isEmpty) false
    else {
      val pattern = s"(?i)(?<![a-z])${java.util.regex.Pattern.quote(t)}(?![a-z])".r
      pattern.findFirstIn(text).nonEmpty
    }
  }
}

/** Gender detection shared by the retrieval provider and the reranker.
  * Normalizes to the canonical strings "men" / "women"; "unisex" or unknown
  * values mean no gender constraint.
  */
object Gender {

  val MenTerms: Seq[String] = Seq("men", "man", "male", "gents")
  val WomenTerms: Seq[String] = Seq("women", "woman", "female", "ladies")

  /** Terms that mark the opposite audience — used for hard exclusion so a
    * men shopper never sees women's products (and vice versa). Includes the
    * kids' variants because "Baby Boy's T-Shirt" is also wrong for "men".
    */
  def oppositeTerms(gender: String): Seq[String] =
    if (gender == "men") WomenTerms ++ Seq("girl", "girls")
    else MenTerms ++ Seq("boy", "boys")

  /** The required gender for these filters, if any. The structured
    * `attributes("gender")` wins; otherwise keywords/attribute values are
    * scanned with word-boundary matching (e.g. keyword "men's watch").
    */
  def requiredFrom(f: ExtractedFilters): Option[String] = {
    f.attributes.get("gender").map(_.trim.toLowerCase) match {
      case Some(g) if MenTerms.contains(g)   => Some("men")
      case Some(g) if WomenTerms.contains(g) => Some("women")
      case Some(_)                           => None // "unisex" etc. → no constraint
      case None =>
        val terms = (f.keywords ++ f.attributes.values).map(_.toLowerCase)
        if (terms.exists(t => WomenTerms.exists(w => TextMatch.containsTerm(t, w)))) Some("women")
        else if (terms.exists(t => MenTerms.exists(m => TextMatch.containsTerm(t, m)))) Some("men")
        else None
    }
  }
}
