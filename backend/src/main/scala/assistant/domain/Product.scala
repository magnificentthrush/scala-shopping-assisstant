package assistant.domain

import upickle.default._
import assistant.domain.NullableOption.nullableOptionRW

object BigDecimalCodec {
  implicit val bigDecimalRW: ReadWriter[BigDecimal] = readwriter[ujson.Value].bimap[BigDecimal](
    bd => ujson.Num(bd.doubleValue),
    json => json match {
      case ujson.Num(n) => BigDecimal(n.toString)
      case ujson.Str(s) => BigDecimal(s)
      case other        => BigDecimal(other.num.toString)
    }
  )
}

import BigDecimalCodec.bigDecimalRW

/** The Product domain case class representing product recommendations.
  * Field names are camelCase matching API_CONTRACT.md.
  * price and originalPrice use BigDecimal per project standards.
  */
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

/** Extracted filters representation produced during LLM Call #2 processing.
  */
case class ExtractedFilters(
    category: Option[String],
    budget: Option[BigDecimal],
    keywords: List[String],
    attributes: Map[String, String]
)

object ExtractedFilters {
  implicit val rw: ReadWriter[ExtractedFilters] = macroRW
}

