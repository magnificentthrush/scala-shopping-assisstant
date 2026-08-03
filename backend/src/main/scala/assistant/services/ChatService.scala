package assistant.services

import assistant.domain.{AppError, Message, Product, Role}
import java.time.Instant
import java.util.UUID

final case class ChatTurn(
    sessionId: String,
    conversationId: String,
    mode: String,
    reply: String,
    followUpQuestion: Option[String],
    products: List[Product],
    userMessage: Message,
    assistantMessage: Message
)

class ChatService {
  def sendMessage(sessionId: String, userId: String, message: String): Either[AppError, ChatTurn] = {
    if (message.trim.isEmpty) {
      Left(AppError.BadRequest("message is required"))
    } else {
      val now = Instant.now()
      val conversationId = UUID.randomUUID().toString
      val userMsg = Message(UUID.randomUUID().toString, Role.User, message.trim, 1, now)
      val assistantMsg = Message(UUID.randomUUID().toString, Role.Assistant, "Demo assistant reply", 2, now)
      Right(
        ChatTurn(
          sessionId = sessionId,
          conversationId = conversationId,
          mode = "demo",
          reply = "Demo assistant reply",
          followUpQuestion = None,
          products = Nil,
          userMessage = userMsg,
          assistantMessage = assistantMsg
        )
      )
    }
  }
}
