package assistant.domain

import upickle.default._

case class Product(
    id: String,
    name: String,
    brand: Option[String],
    category: String,
    price: BigDecimal,
    originalPrice: Option[BigDecimal],
    rating: Option[String],
    description: Option[String],
    imageUrl: Option[String],
    productUrl: Option[String],
    productSpecifications: Option[String]
)

object Product {
  implicit val rw: ReadWriter[Product] = macroRW
}

case class ExtractedFilters(
    category: Option[String],
    budget: Option[BigDecimal],
    keywords: List[String],
    attributes: Map[String, String]
)

object ExtractedFilters {
  implicit val rw: ReadWriter[ExtractedFilters] = macroRW
}