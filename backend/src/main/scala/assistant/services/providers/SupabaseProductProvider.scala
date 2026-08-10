package assistant.services.providers

import assistant.domain.{Filters, Product}
import assistant.repo.ProductRepo

/** The sole concrete `ProductProvider` today, per ARCHITECTURE.md §5. It is
  * a thin adapter over `ProductRepo` — all the actual SQL lives in the repo
  * layer; this class exists purely so that everything above it (services)
  * codes against the `ProductProvider` trait instead of a concrete repo
  * class, which is what makes swapping providers later "a one-file change,
  * not a rewrite."
  */
class SupabaseProductProvider(productRepo: ProductRepo) extends ProductProvider {
  override def search(filters: Filters): List[Product] = productRepo.search(filters, limit = 30)
  override def findById(id: String): Option[Product] = productRepo.findById(id)
  override def list(query: Option[String], budget: Option[Double], limit: Int): List[Product] =
    productRepo.list(query, budget, limit)
}
