package assistant.http

import assistant.domain._
import upickle.default._
import java.time.Instant
import java.time.format.DateTimeFormatter

/** Every JSON shape the frontend actually sees, matching API_CONTRACT.md
  * field-for-field (camelCase on the wire). These are deliberately separate
  * from the `domain` case classes in `assistant.domain.Models` — a
  * `Conversation` (domain) has a `userId` field that must NEVER be sent to
  * the frontend, and `ConversationSummaryDto` (here) simply doesn't have
  * one, so there is no way to accidentally leak it by forgetting a filter
  * somewhere. Routes build a Dto FROM a domain object; they never send a
  * domain object directly.
  */
object Dto {

  // Instant <-> ISO-8601 string, since JSON has no native date type and
  // API_CONTRACT.md specifies `createdAt: string // ISO-8601`.
  implicit val instantRW: ReadWriter[Instant] =
    readwriter[String].bimap[Instant](
      i => DateTimeFormatter.ISO_INSTANT.format(i),
      s => Instant.parse(s)
    )

  final case class UserDto(id: String, fullName: String, email: String)
  object UserDto {
    implicit val rw: ReadWriter[UserDto] = macroRW
    def from(u: User): UserDto = UserDto(u.id, u.fullName, u.email)
  }

  final case class ProductDto(
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
  object ProductDto {
    implicit val rw: ReadWriter[ProductDto] = macroRW
    def from(p: Product): ProductDto =
      ProductDto(
        p.id,
        p.name,
        p.brand,
        p.category,
        p.price,
        p.originalPrice,
        p.rating,
        p.description,
        p.imageUrl,
        p.productUrl,
        p.productSpecifications
      )
  }

  final case class MessageDto(
      id: String,
      role: String,
      content: String,
      sequenceNumber: Int,
      createdAt: Instant,
      products: Option[List[ProductDto]]
  )
  object MessageDto {
    implicit val rw: ReadWriter[MessageDto] = macroRW

    /** `includeProductsField` mirrors API_CONTRACT.md's examples: assistant
      * rows carry a (possibly empty) `products` array, user rows omit the
      * field entirely. The schema does not persist which exact products
      * were shown on a historical turn (only `filters_snapshot`), so on
      * replay (resume) this is always an empty list for assistant rows —
      * only the LIVE turn's top-level `products` field carries the real
      * top-5 for that turn.
      */
    def from(m: Message, products: List[Product] = Nil): MessageDto = {
      val productsField = m.role match {
        case Role.Assistant => Some(products.map(ProductDto.from))
        case Role.User       => None
      }
      MessageDto(m.id, m.role.value, m.content, m.sequenceNumber, m.createdAt, productsField)
    }
  }

  final case class ConversationSummaryDto(
      id: String,
      title: Option[String],
      createdAt: Instant,
      updatedAt: Instant,
      lastMessageAt: Instant
  )
  object ConversationSummaryDto {
    implicit val rw: ReadWriter[ConversationSummaryDto] = macroRW
    def from(c: Conversation): ConversationSummaryDto =
      ConversationSummaryDto(c.id, c.title, c.createdAt, c.updatedAt, c.lastMessageAt)
  }

  final case class ErrorBody(error: String, code: Option[String])
  object ErrorBody {
    implicit val rw: ReadWriter[ErrorBody] = macroRW
  }

  // ---- Requests ----

  final case class RegisterRequest(fullName: String, email: String, password: String)
  object RegisterRequest { implicit val rw: ReadWriter[RegisterRequest] = macroRW }

  final case class LoginRequest(email: String, password: String)
  object LoginRequest { implicit val rw: ReadWriter[LoginRequest] = macroRW }

  final case class RenameRequest(title: String)
  object RenameRequest { implicit val rw: ReadWriter[RenameRequest] = macroRW }

  final case class SendMessageRequest(message: String)
  object SendMessageRequest { implicit val rw: ReadWriter[SendMessageRequest] = macroRW }

  // ---- Responses ----

  final case class AuthResponse(user: UserDto, token: String)
  object AuthResponse { implicit val rw: ReadWriter[AuthResponse] = macroRW }

  final case class NewChatResponse(conversationId: String, sessionId: String, title: Option[String], messages: List[MessageDto])
  object NewChatResponse { implicit val rw: ReadWriter[NewChatResponse] = macroRW }

  final case class ConversationsListResponse(conversations: List[ConversationSummaryDto])
  object ConversationsListResponse { implicit val rw: ReadWriter[ConversationsListResponse] = macroRW }

  final case class ResumeResponse(conversationId: String, sessionId: String, title: Option[String], messages: List[MessageDto])
  object ResumeResponse { implicit val rw: ReadWriter[ResumeResponse] = macroRW }

  final case class ChatMessageResponse(
      sessionId: String,
      conversationId: String,
      mode: String,
      reply: String,
      followUpQuestion: Option[String],
      products: List[ProductDto],
      userMessage: MessageDto,
      assistantMessage: MessageDto
  )
  object ChatMessageResponse { implicit val rw: ReadWriter[ChatMessageResponse] = macroRW }

  final case class ProductsListResponse(products: List[ProductDto])
  object ProductsListResponse { implicit val rw: ReadWriter[ProductsListResponse] = macroRW }
}
