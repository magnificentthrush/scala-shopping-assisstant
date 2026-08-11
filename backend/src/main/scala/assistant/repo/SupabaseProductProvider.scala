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

  override def search(filters: ExtractedFilters, limit: Int = 30): Seq[Product] = {
    val searchText = (filters.category.toSeq ++ filters.keywords ++ filters.attributes.values)
      .map(_.trim)
      .filter(_.nonEmpty)
      .mkString(" ")

    val priceParam = filters.budget.map(b => s"lte.$b").getOrElse("gt.0")
    val baseParams = Map(
      "category" -> "not.is.null",
      "price" -> priceParam,
      "limit" -> limit.toString
    )
    val params = if (searchText.nonEmpty) {
      baseParams + ("search_vector" -> s"plfts(english).$searchText")
    } else {
      baseParams
    }

    val json = client.get(Table, params)
    read[Seq[ProductRow]](json).map(_.toProduct)
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
