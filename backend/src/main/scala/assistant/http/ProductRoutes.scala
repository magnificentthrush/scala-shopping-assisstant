package assistant.http

import assistant.http.Dto._
import assistant.services.ProductService
import upickle.default._

/** Route -> Service -> ProductProvider -> Supabase, for the read-only
  * catalog/debug endpoints. No auth required — browsing the catalog is a
  * public action, same as browsing a shop window before logging in.
  */
class ProductRoutes(productService: ProductService) extends cask.Routes with RouteSupport {

  /** GET /api/products?query=&budget=&limit= — plain catalog listing. */
  @cask.get("/api/products")
  def list(query: Option[String] = None, budget: Option[Double] = None, limit: Int = 20): cask.Response[ujson.Value] =
    handle {
      val products = productService.list(query, budget, limit).map(ProductDto.from)
      ok(writeJs(ProductsListResponse(products)))
    }

  /** GET /api/products/:id — single product lookup. */
  @cask.get("/api/products/:id")
  def get(id: String): cask.Response[ujson.Value] = handle {
    respond(productService.get(id))(p => ok(writeJs(ProductDto.from(p))))
  }

  /** GET /api/search?query=&budget=&limit= — alias of `list`, kept as its
    * own route because the mentor brief/API surface names it separately
    * from the catalog endpoint; same underlying service call.
    */
  @cask.get("/api/search")
  def search(query: Option[String] = None, budget: Option[Double] = None, limit: Int = 20): cask.Response[ujson.Value] =
    handle {
      val products = productService.list(query, budget, limit).map(ProductDto.from)
      ok(writeJs(ProductsListResponse(products)))
    }

  initialize()
}
