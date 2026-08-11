package assistant.repo

import assistant.domain.{ExtractedFilters, Product}
import assistant.domain.NullableOption.nullableOptionRW
import assistant.domain.BigDecimalCodec.bigDecimalRW
import upickle.default._
import upickle.implicits.key

trait ProductProvider {
  def search(filters: ExtractedFilters, limit: Int = 30): Seq[Product]
}

class SupabaseProductProvider(client: SupabaseRestClient) extends ProductProvider {
  private val Table: String = "products"

  /** Fallback ladder (docs/retrievalPlan.md §3):
    *   rung 1 — full plfts term set + price
    *   rung 2 — category=eq + price (keyword-free; uses the grounded category)
    *   rung 3 — websearch on the single most salient term + price
    * Short-circuits on the first non-empty rung.
    */
  override def search(filters: ExtractedFilters, limit: Int = 30): Seq[Product] = {
    val searchText = (filters.category.toSeq ++ filters.keywords ++ filters.attributes.values)
      .map(_.trim)
      .filter(_.nonEmpty)
      .mkString(" ")
    val priceParam = filters.budget.map(b => s"lte.$b").getOrElse("gt.0")

    def baseParams(extra: Map[String, String]): Map[String, String] =
      Map(
        "category" -> "not.is.null",
        "price" -> priceParam,
        "limit" -> limit.toString
      ) ++ extra

    def run(params: Map[String, String]): Seq[Product] =
      read[Seq[ProductRow]](client.get(Table, params)).map(_.toProduct)

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

    // Rung 2: drop the FTS keywords and search by grounded category + price only.
    val rung2 = filters.category match {
      case Some(cat) =>
        run(Map(
          "category" -> s"eq.$cat",
          "price" -> priceParam,
          "limit" -> limit.toString
        ))
      case None => Seq.empty
    }

    if (rung2.nonEmpty) {
      println(s"[retrieval] rung 2 (category + price) returned ${rung2.size} candidates")
      return rung2
    }

    // Rung 3: websearch on the single most salient term (longest keyword,
    // falling back to any attribute value) — websearch_to_tsquery tolerates
    // user-ish phrasing better than plainto_tsquery for single terms.
    val salient = (filters.keywords ++ filters.attributes.values)
      .map(_.trim)
      .filter(_.nonEmpty)
      .sortBy(-_.length)
      .headOption

    val rung3 = salient match {
      case Some(term) =>
        run(baseParams(Map("search_vector" -> s"wfts(english).$term")))
      case None => Seq.empty
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
