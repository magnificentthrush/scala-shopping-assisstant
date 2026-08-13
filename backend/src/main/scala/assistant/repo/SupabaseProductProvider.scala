package assistant.repo

import assistant.domain.{ExtractedFilters, Gender, Product, TextMatch}
import assistant.domain.NullableOption.nullableOptionRW
import assistant.domain.BigDecimalCodec.bigDecimalRW
import upickle.default._
import upickle.implicits.key

trait ProductProvider {
  /** Runs the retrieval ladder against Supabase and returns matching products. */
  def search(filters: ExtractedFilters, limit: Int = 30): Seq[Product]
}

class SupabaseProductProvider(client: SupabaseRestClient) extends ProductProvider {
  private val Table: String = "products"

  /** Fallback ladder (docs/retrievalPlan.md §3):
    *   rung 1  — full plfts term set + price
    *   rung 1b — when gender is known: plfts of gender + the single most
    *             salient intent term + price (relaxes the AND before giving
    *             up on keywords entirely)
    *   rung 2  — category=eq + price; when gender is known, also gender FTS
    *             with a negated opposite-gender term
    *   rung 3  — websearch on the single most salient term + price
    * Short-circuits on the first non-empty rung.
    */
  override def search(filters: ExtractedFilters, limit: Int = 30): Seq[Product] = {
    val gender = Gender.requiredFrom(filters)
    val searchText = (filters.category.toSeq ++ filters.keywords ++ filters.attributes.values)
      .map(_.trim)
      .filter(_.nonEmpty)
      .mkString(" ")
    val priceParam = filters.budget.map(b => s"lte.$b").getOrElse("gt.0")

    // Shared PostgREST filters (in-stock, price, limit) plus any extra rung filters.
    def baseParams(extra: Map[String, String]): Map[String, String] =
      Map(
        "category" -> "not.is.null",
        "price" -> priceParam,
        "limit" -> limit.toString
      ) ++ extra

    // Fetches product rows for one retrieval rung and maps them to domain Products.
    def run(params: Map[String, String]): Seq[Product] =
      read[Seq[ProductRow]](client.get(Table, params)).map(_.toProduct)

    // The single most salient non-gender term (longest keyword/attribute
    // value) — used by rungs 1b and 3.
    val salient = (filters.keywords ++ filters.attributes.values)
      .map(_.trim)
      .filter(_.nonEmpty)
      .filterNot(t => gender.exists(g => t.equalsIgnoreCase(g)))
      .sortBy(-_.length)
      .headOption

    // Rung 1: strict full-text match on all extracted terms.
    val rung1 =
      if (searchText.nonEmpty) {
        run(baseParams(Map("search_vector" -> s"plfts(english).$searchText")))
      } else {
        run(baseParams(Map.empty))
      }

    if (rung1.nonEmpty) {
      println(s"[retrieval] rung 1 (plfts full term set) returned ${rung1.size} candidates")
      return rung1
    }

    // Rung 1b: the full AND is often too strict once gender is folded in
    // (e.g. "Clothing shirt plain black men" matches nothing even though
    // hundreds of men's shirts exist). Retry with just gender + the salient
    // intent term before falling back to a keyword-free category search.
    val rung1b = (gender, salient) match {
      case (Some(g), Some(term)) =>
        run(baseParams(Map("search_vector" -> s"plfts(english).$g $term")))
      case _ => Seq.empty
    }

    if (rung1b.nonEmpty) {
      println(s"[retrieval] rung 1b (plfts gender + salient '${salient.getOrElse("")}') returned ${rung1b.size} candidates")
      return rung1b
    }

    // Rung 2: drop the FTS keywords and search by grounded category + price.
    // When gender is known, keep it as a hard DB-side condition: require the
    // gender lexeme and negate the opposite one (to_tsquery `g & !opposite`)
    // so the fallback can't refill the pool with the wrong audience.
    val rung2 = filters.category match {
      case Some(cat) =>
        val genderParam = gender match {
          case Some(g) => Map("search_vector" -> s"fts(english).$g & !${Gender.oppositeTerms(g).head}")
          case None    => Map.empty
        }
        run(Map(
          "category" -> s"eq.$cat",
          "price" -> priceParam,
          "limit" -> limit.toString
        ) ++ genderParam)
      case None => Seq.empty
    }

    if (rung2.nonEmpty) {
      println(s"[retrieval] rung 2 (category + price${gender.map(g => s" + gender=$g").getOrElse("")}) returned ${rung2.size} candidates")
      return rung2
    }

    // Rung 3: websearch on the single most salient term — websearch_to_tsquery
    // tolerates user-ish phrasing better than plainto_tsquery for single terms.
    val rung3raw = salient match {
      case Some(term) =>
        run(baseParams(Map("search_vector" -> s"wfts(english).$term")))
      case None => Seq.empty
    }

    // Rung-3 quality gate: FTS matches product_specifications too, so an
    // electrical switch with {"key": "Waterproof", "value": "No"} can surface
    // for "waterproof hiking shoes". Require keyword overlap in user-visible
    // text (name/brand/category/description) — at least 2 hits when the query
    // had 2+ terms, else 1 — so vocabulary accidents don't reach the user.
    // Matching is word-boundary based: "women" must not count as "men".
    // When gender is known, a gender hit is mandatory for rung-3 results.
    val terms = (filters.keywords ++ filters.attributes.values)
      .map(_.trim.toLowerCase)
      .filter(_.nonEmpty)
      .distinct
    // User-visible fields only — spec-only FTS hits must not count as keyword matches.
    def visibleText(p: Product): String =
      (Seq(p.name, p.category) ++ p.brand.toSeq ++ p.description.toSeq).mkString(" ").toLowerCase
    // How many extracted query terms appear in the product's visible text.
    def termHits(p: Product): Int = terms.count(t => TextMatch.containsTerm(visibleText(p), t))
    val minHits = if (terms.size >= 2) 2 else 1
    val rung3 =
      if (terms.isEmpty && gender.isEmpty) rung3raw
      else rung3raw.filter { p =>
        val genderOk = gender.forall(g => TextMatch.containsTerm(visibleText(p), g))
        genderOk && (terms.isEmpty || termHits(p) >= minHits)
      }

    if (rung3.nonEmpty) {
      println(s"[retrieval] rung 3 (websearch '${salient.getOrElse("")}') returned ${rung3.size} candidates")
    } else {
      println(s"[retrieval] all rungs exhausted — zero candidates")
    }
    rung3
  }
}

private case class ProductRow(
    id: String,
    name: String,
    brand: Option[String],
    category: Option[String],
    price: Option[BigDecimal],
    @key("original_price") originalPrice: Option[BigDecimal],
    rating: Option[String],
    description: Option[String],
    @key("image_url") imageUrl: Option[String],
    @key("product_url") productUrl: Option[String],
    @key("product_specifications") productSpecifications: Option[String]
) {
  /** Maps a PostgREST product row onto the domain Product, filling missing price/category. */
  def toProduct: Product = Product(
    id = id,
    name = name,
    brand = brand,
    category = category.getOrElse(""),
    price = price.getOrElse(BigDecimal(0)),
    originalPrice = originalPrice,
    rating = rating,
    description = description,
    imageUrl = imageUrl,
    productUrl = productUrl,
    productSpecifications = productSpecifications
  )
}

private object ProductRow {
  implicit val rw: ReadWriter[ProductRow] = macroRW
}
