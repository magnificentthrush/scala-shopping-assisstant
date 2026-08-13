package assistant.services

/** Deterministic USD-vs-INR budget guard (docs/ currency-aware budget guard plan).
  *
  * The store's catalog is priced entirely in INR, but users occasionally state a
  * budget in dollars ("$10 shirt", "under 1000 dollars"). Rather than trusting the
  * LLM to convert or sanity-check that figure (it hallucinates numbers under load —
  * see AssistantService's filter-summary comment), this module deterministically:
  *   - detects the first dollar amount in the raw user message,
  *   - converts it to INR at a fixed rate when it's plausible for the category, or
  *   - flags it for clarification when it's wildly implausible (the user likely
  *     meant to type ₹, not $).
  *
  * Pure and side-effect free so it's trivially unit-testable without an LLM.
  */
object CurrencyGuard {

  /** Matches `$100`, `$1,000`, `$12.50`, `100 dollars`, `100 usd`, `50 bucks`. */
  private val DollarPattern =
    """(?i)\$\s?(\d[\d,]*(?:\.\d+)?)|(\d[\d,]*(?:\.\d+)?)\s?(?:usd|dollars?|bucks)""".r

  /** Single source of truth for the conversion rate — kept equal to the rate
    * quoted in AssistantPrompt's GROUNDING section.
    */
  val UsdToInrRate: BigDecimal = BigDecimal(83)

  /** Max sensible USD price per category, matched case-insensitively against
    * `filters.category` with substring tolerance (e.g. "t-shirts"/"shirts" both
    * match the "shirt" keyword). Values are derived roughly from the catalog's
    * price distribution (median ~₹550 per docs/retrievalPlan.md) with generous
    * headroom so genuine budgets aren't misflagged.
    */
  private val CategoryCeilingsUsd: Seq[(Set[String], BigDecimal)] = Seq(
    Set("shirt", "cloth", "apparel", "footwear", "shoe", "sneaker", "boot", "sandal", "jacket", "dress", "jean") ->
      BigDecimal(150),
    Set("watch") -> BigDecimal(500),
    Set("mobile", "phone", "smartphone") -> BigDecimal(1500),
    Set("laptop", "computer", "notebook") -> BigDecimal(2000),
    Set("tv", "television", "home entertainment") -> BigDecimal(3000)
  )

  /** Ceiling applied when the category is unknown or doesn't match any of the
    * known keyword sets above.
    */
  val DefaultCeilingUsd: BigDecimal = BigDecimal(400)

  sealed trait Resolution
  case object NoDollarAmount extends Resolution
  case class Convert(inr: BigDecimal) extends Resolution
  case class Clarify(usdAmount: BigDecimal, inrEquivalent: BigDecimal) extends Resolution

  /** Finds the first dollar amount stated in `message`, converts it to INR, and
    * decides whether that figure is plausible for `category` or warrants asking
    * the user to clarify. Returns `NoDollarAmount` when no `$`/dollar-word amount
    * is present at all — bare numbers ("under 1000") are already treated as INR
    * upstream and never reach this guard as dollar amounts.
    */
  def resolve(message: String, category: Option[String]): Resolution =
    firstDollarAmount(message) match {
      case None => NoDollarAmount
      case Some(usd) =>
        val inr = usd * UsdToInrRate
        if (usd <= ceilingFor(category)) Convert(inr)
        else Clarify(usd, inr)
    }

  // Parses the first $ / USD / "dollars" / "bucks" amount from the raw message.
  private def firstDollarAmount(message: String): Option[BigDecimal] =
    DollarPattern.findFirstMatchIn(message).flatMap { m =>
      Option(m.group(1)).orElse(Option(m.group(2))).map(s => BigDecimal(s.replace(",", "")))
    }

  // Max plausible USD budget for this category; falls back to DefaultCeilingUsd.
  private def ceilingFor(category: Option[String]): BigDecimal =
    category
      .map(_.toLowerCase)
      .flatMap(cat => CategoryCeilingsUsd.find { case (keywords, _) => keywords.exists(cat.contains) }.map(_._2))
      .getOrElse(DefaultCeilingUsd)
}
