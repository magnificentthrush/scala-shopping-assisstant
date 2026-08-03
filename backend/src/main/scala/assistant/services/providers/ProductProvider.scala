package assistant.services.providers

import assistant.domain.Product

trait ProductProvider {
  def search(query: Option[String], budget: Option[Double], limit: Int): List[Product]
}
