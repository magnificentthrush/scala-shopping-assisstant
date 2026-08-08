package assistant.repo

import assistant.db.{Database, Rs}
import assistant.domain.{Filters, Product}
import java.sql.ResultSet

/** All SQL for the `products` table. This is the ONLY class in the codebase
  * allowed to query `products` directly — `SupabaseProductProvider`
  * (services/providers) wraps it behind the `ProductProvider` interface so
  * business logic never depends on this repo, or on Postgres, directly
  * (ARCHITECTURE.md §5: "Gemma never writes SQL", and swapping the storage
  * layer later is a one-file change).
  */
class ProductRepo(db: Database) {

  private def fromRow(rs: ResultSet): Product = Product(
    id = rs.getString("id"),
    name = rs.getString("name"),
    brand = Rs.optString(rs, "brand"),
    category = Rs.optString(rs, "category"),
    price = Rs.optDouble(rs, "price"),
    originalPrice = Rs.optDouble(rs, "original_price"),
    rating = Rs.optString(rs, "rating"),
    description = Rs.optString(rs, "description"),
    imageUrl = Rs.optString(rs, "image_url"),
    productUrl = Rs.optString(rs, "product_url"),
    productSpecifications = Rs.optString(rs, "product_specifications")
  )

  def findById(id: String): Option[Product] = db.withConnection { conn =>
    val stmt = conn.prepareStatement("SELECT * FROM products WHERE id = ?")
    stmt.setString(1, id)
    val rs = stmt.executeQuery()
    try if (rs.next()) Some(fromRow(rs)) else None
    finally { rs.close(); stmt.close() }
  }

  /** Full-text search (`search_vector @@ plainto_tsquery(...)`) plus SQL
    * filters for category/budget, returning the top `limit` (30, per
    * ARCHITECTURE.md §5) candidates ranked by `ts_rank`. This is the ONLY
    * step where a query touches the database; the reranker afterward
    * (services/Reranker.scala) works purely in memory over these 30 rows —
    * it never re-queries Postgres.
    *
    * `keywords` are joined into one `plainto_tsquery` input; `category` and
    * `budget` become `AND`-ed SQL predicates so we don't over-filter with
    * full-text alone (e.g. a $50 budget should exclude a $500 product even
    * if the text matches well).
    */
  def search(filters: Filters, limit: Int = 30): List[Product] = db.withConnection { conn =>
    val searchText = (filters.keywords ++ filters.category.toList).mkString(" ").trim

    val conditions = scala.collection.mutable.ListBuffer[String]()
    val params = scala.collection.mutable.ListBuffer[Any]()

    if (searchText.nonEmpty) {
      conditions += "search_vector @@ plainto_tsquery('english', ?)"
      params += searchText
    }
    filters.category.foreach { c =>
      conditions += "category ILIKE ?"
      params += s"%$c%"
    }
    filters.budget.foreach { b =>
      conditions += "price IS NOT NULL AND price <= ?"
      params += b
    }

    val whereClause = if (conditions.isEmpty) "TRUE" else conditions.mkString(" AND ")
    val orderClause =
      if (searchText.nonEmpty) "ORDER BY ts_rank(search_vector, plainto_tsquery('english', ?)) DESC"
      else "ORDER BY price ASC NULLS LAST"

    val sql = s"SELECT * FROM products WHERE $whereClause $orderClause LIMIT ?"
    val stmt = conn.prepareStatement(sql)

    var idx = 1
    params.foreach { p =>
      p match {
        case s: String => stmt.setString(idx, s)
        case d: Double  => stmt.setDouble(idx, d)
      }
      idx += 1
    }
    if (searchText.nonEmpty) {
      stmt.setString(idx, searchText)
      idx += 1
    }
    stmt.setInt(idx, limit)

    val rs = stmt.executeQuery()
    try Iterator.continually(rs).takeWhile(_.next()).map(fromRow).toList
    finally { rs.close(); stmt.close() }
  }

  /** Plain listing for `GET /api/products` (catalog/debug route) — simple
    * keyword + budget filter, no ranking required.
    */
  def list(query: Option[String], budget: Option[Double], limit: Int): List[Product] = db.withConnection { conn =>
    val conditions = scala.collection.mutable.ListBuffer[String]()
    val params = scala.collection.mutable.ListBuffer[Any]()
    query.foreach { q =>
      conditions += "search_vector @@ plainto_tsquery('english', ?)"
      params += q
    }
    budget.foreach { b =>
      conditions += "price IS NOT NULL AND price <= ?"
      params += b
    }
    val whereClause = if (conditions.isEmpty) "TRUE" else conditions.mkString(" AND ")
    val stmt = conn.prepareStatement(s"SELECT * FROM products WHERE $whereClause LIMIT ?")
    var idx = 1
    params.foreach { p =>
      p match {
        case s: String => stmt.setString(idx, s)
        case d: Double  => stmt.setDouble(idx, d)
      }
      idx += 1
    }
    stmt.setInt(idx, limit)
    val rs = stmt.executeQuery()
    try Iterator.continually(rs).takeWhile(_.next()).map(fromRow).toList
    finally { rs.close(); stmt.close() }
  }
}
