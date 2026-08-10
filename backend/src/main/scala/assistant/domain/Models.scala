package assistant.domain

import java.time.Instant

/** Domain models: plain Scala case classes that mirror the six tables in
  * database-schema.md. These are internal — the JSON shapes the frontend
  * actually sees (camelCase, sometimes a different shape) live in
  * `assistant.http.Dto` and are built FROM these, not instead of these.
  * Keeping the two separate means a wire-format change (frontend asks for a
  * new field) never forces a schema change, and vice versa.
  */

final case class User(
    id: String,
    fullName: String,
    email: String,
    passwordHash: String,
    createdAt: Instant,
    updatedAt: Instant
)

final case class Conversation(
    id: String,
    userId: String,
    title: Option[String],
    createdAt: Instant,
    updatedAt: Instant,
    lastMessageAt: Instant
)

/** `role` is constrained at the DB level to "user" | "assistant". We model
  * it as a closed Scala type instead of a raw String so the compiler (not a
  * runtime check) rules out a typo like "asistant" ever being constructed.
  */
sealed trait Role { def value: String }
object Role {
  case object User extends Role { val value = "user" }
  case object Assistant extends Role { val value = "assistant" }

  def fromString(s: String): Role = s match {
    case "user"      => User
    case "assistant" => Assistant
    case other        => throw new IllegalArgumentException(s"Unknown role: $other")
  }
}

final case class Message(
    id: String,
    conversationId: String,
    sequenceNumber: Int,
    role: Role,
    content: String,
    filtersSnapshot: Option[Filters],
    safe: Option[Boolean],
    createdAt: Instant
)

/** Structured filters Gemma extracts from the conversation, e.g.
  * { category: "hiking shoes", budget: 120, waterproof: "true" }.
  * `attributes` is an open bag for anything that isn't category/budget/
  * keywords — this is exactly what lets ProductProvider stay generic
  * instead of hard-coding every possible product attribute.
  */
final case class Filters(
    category: Option[String] = None,
    budget: Option[Double] = None,
    keywords: List[String] = Nil,
    attributes: Map[String, String] = Map.empty
)

final case class ConversationState(
    conversationId: String,
    filters: Filters,
    updatedAt: Instant
)

final case class ChatSession(
    id: String,
    conversationId: String,
    userId: String,
    createdAt: Instant,
    lastActiveAt: Instant,
    expiresAt: Option[Instant]
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
