package assistant.services

import assistant.domain.{AppError, Product}
import assistant.services.providers.ProductProvider

/** Service layer for the catalog/debug routes (`GET /api/products`,
  * `GET /api/products/{id}`). Thin on purpose — there is no business logic
  * here beyond "ask the provider" — but it exists anyway so routes never
  * hold a `ProductProvider` reference directly. That keeps the dependency
  * direction consistent across the whole codebase: routes -> services ->
  * providers/repos, always in that order, never routes -> repos directly.
  */
class ProductService(productProvider: ProductProvider) {

  def list(query: Option[String], budget: Option[Double], limit: Int): List[Product] =
    productProvider.list(query, budget, math.min(math.max(limit, 1), 100))

  def get(id: String): Either[AppError, Product] =
    productProvider.findById(id).toRight(AppError.NotFound(s"Product $id not found"))
}
