package assistant.domain

import java.time.Instant

sealed trait AppError {
  def message: String
  def code: String
}

object AppError {
  final case class BadRequest(message: String) extends AppError { val code = "BAD_REQUEST" }
  final case class Unauthorized(message: String) extends AppError { val code = "UNAUTHORIZED" }
  final case class Forbidden(message: String) extends AppError { val code = "FORBIDDEN" }
  final case class NotFound(message: String) extends AppError { val code = "NOT_FOUND" }
  final case class Conflict(message: String) extends AppError { val code = "CONFLICT" }
  final case class Rejected(message: String) extends AppError { val code = "REJECTED" }
  final case class AssistantFailed(message: String) extends AppError { val code = "ASSISTANT_FAILED" }
  final case class UpstreamUnavailable(message: String) extends AppError { val code = "UPSTREAM_UNAVAILABLE" }
  final case class Internal(message: String) extends AppError { val code = "INTERNAL_ERROR" }
}

sealed trait Role {
  def value: String
}

object Role {
  case object User extends Role { val value = "user" }
  case object Assistant extends Role { val value = "assistant" }
}

final case class User(
    id: String,
    fullName: String,
    email: String,
    passwordHash: String
)

final case class Product(
    id: String,
    name: String,
    brand: Option[String],
    category: Option[String],
    price: Option[Double],
    originalPrice: Option[Double],
    rating: Option[String],
    description: Option[String],
    imageUrl: Option[String],
    productUrl: Option[String],
    productSpecifications: Option[String]
)

final case class Message(
    id: String,
    role: Role,
    content: String,
    sequenceNumber: Int,
    createdAt: Instant
)

final case class Conversation(
    id: String,
    title: Option[String],
    createdAt: Instant,
    updatedAt: Instant,
    lastMessageAt: Instant
)
