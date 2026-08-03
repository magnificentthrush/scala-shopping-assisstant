package assistant.services

import assistant.domain.{AppError, Product}

class ProductService {
  def list(query: Option[String] = None, budget: Option[Double] = None, limit: Int = 20): List[Product] =
    Nil

  def get(id: String): Either[AppError, Product] =
    Left(AppError.NotFound(s"Product not found: $id"))
}
