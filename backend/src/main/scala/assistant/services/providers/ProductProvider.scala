package assistant.services.providers

import assistant.domain.{Filters, Product}

/** ARCHITECTURE.md §5: "Business logic never calls Supabase Postgres
  * directly for product data. It depends only on a `ProductProvider`
  * interface." `ChatService` and `ProductService` hold a
  * `ProductProvider`, not a `ProductRepo` — swapping the storage layer
  * later (a different store, a cache, a vector DB) means writing a new
  * implementation of this trait, not touching any caller.
  */
trait ProductProvider {

  /** Full-text + filter search, returning up to 30 candidates ranked by
    * relevance. Callers pass this to `Reranker.rerank` to get the final
    * top 5 shown to the user.
    */
  def search(filters: Filters): List[Product]

  def findById(id: String): Option[Product]

  def list(query: Option[String], budget: Option[Double], limit: Int): List[Product]
}
