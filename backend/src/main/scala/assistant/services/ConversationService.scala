package assistant.services

import assistant.domain.{AppError, Conversation, Message, Role}
import java.time.Instant
import java.util.UUID

final case class Session(id: String)
final case class ConversationStartResult(conversation: Conversation, session: Session)
final case class ConversationResumeResult(conversation: Conversation, session: Session, messages: List[Message])

class ConversationService {
  def startNewChat(userId: String): ConversationStartResult = {
    val now = Instant.now()
    val conversation = Conversation(UUID.randomUUID().toString, None, now, now, now)
    val session = Session(UUID.randomUUID().toString)
    ConversationStartResult(conversation, session)
  }

  def listForUser(userId: String): List[Conversation] = Nil

  def resume(id: String, userId: String): Either[AppError, ConversationResumeResult] =
    Right(ConversationResumeResult(
      conversation = Conversation(id, None, Instant.now(), Instant.now(), Instant.now()),
      session = Session(UUID.randomUUID().toString),
      messages = Nil
    ))

  def rename(id: String, userId: String, title: String): Either[AppError, Conversation] =
    Right(Conversation(id, Some(title.trim), Instant.now(), Instant.now(), Instant.now()))

  def delete(id: String, userId: String): Either[AppError, Unit] = Right(())
}
