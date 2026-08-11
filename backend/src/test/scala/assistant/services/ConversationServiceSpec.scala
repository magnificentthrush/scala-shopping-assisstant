package assistant.services

import assistant.domain._
import assistant.repo._
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.util.UUID

private class FakeChatSessionRepo extends ChatSessionRepo(null) {
  var sessions = Map.empty[String, ChatSession]

  override def insert(userId: String, conversationId: Option[String] = None): ChatSession = {
    val session = ChatSession(
      id = s"session-${UUID.randomUUID()}",
      conversationId = conversationId,
      userId = userId,
      createdAt = "2026-08-11T12:00:00Z",
      lastActiveAt = "2026-08-11T12:00:00Z",
      expiresAt = Some("2026-08-11T12:30:00Z")
    )
    sessions += (session.id -> session)
    session
  }

  override def findById(sessionId: String): Option[ChatSession] =
    sessions.get(sessionId)

  override def setConversationId(sessionId: String, conversationId: String): Unit =
    sessions.get(sessionId).foreach { s =>
      sessions += (sessionId -> s.copy(conversationId = Some(conversationId)))
    }

  override def touchActivity(sessionId: String): Unit =
    sessions.get(sessionId).foreach { s =>
      sessions += (sessionId -> s.copy(lastActiveAt = "2026-08-11T12:05:00Z"))
    }
}

private class FakeConversationRepo extends ConversationRepo(null) {
  var conversations = Map.empty[String, Conversation]

  override def insert(userId: String): Conversation = {
    val conv = Conversation(
      id = s"conv-${UUID.randomUUID()}",
      userId = userId,
      title = None,
      createdAt = "2026-08-11T12:00:00Z",
      updatedAt = "2026-08-11T12:00:00Z",
      lastMessageAt = "2026-08-11T12:00:00Z"
    )
    conversations += (conv.id -> conv)
    conv
  }

  override def findById(conversationId: String): Option[Conversation] =
    conversations.get(conversationId)

  override def listByUser(userId: String): Seq[Conversation] =
    conversations.values.filter(_.userId == userId).toSeq

  override def updateTitle(
      conversationId: String,
      userId: String,
      title: String
  ): Option[Conversation] =
    conversations.get(conversationId).filter(_.userId == userId).map { c =>
      val updated = c.copy(title = Some(title), updatedAt = "2026-08-11T12:10:00Z")
      conversations += (conversationId -> updated)
      updated
    }

  override def delete(conversationId: String, userId: String): Boolean =
    conversations.get(conversationId).exists(_.userId == userId) match {
      case true =>
        conversations -= conversationId
        true
      case false =>
        false
    }

  override def touchLastMessageAt(conversationId: String): Unit =
    conversations.get(conversationId).foreach { c =>
      conversations += (conversationId -> c.copy(lastMessageAt = "2026-08-11T12:05:00Z"))
    }
}

private class FakeConversationStateRepo extends ConversationStateRepo(null) {
  var states = Map.empty[String, String]

  override def insertEmpty(conversationId: String): Unit =
    states += (conversationId -> "{}")
}

private class FakeMessageRepo extends MessageRepo(null) {
  var messages = Seq.empty[MessageRow]

  override def nextSequenceNumber(conversationId: String): Int = {
    val seqs = messages.filter(_.conversationId == conversationId).map(_.sequenceNumber)
    if (seqs.nonEmpty) seqs.max + 1 else 1
  }

  override def insertUserMessage(conversationId: String, content: String): MessageRow = {
    val seq = nextSequenceNumber(conversationId)
    val row = MessageRow(
      id = s"msg-${UUID.randomUUID()}",
      conversationId = conversationId,
      sequenceNumber = seq,
      role = "user",
      content = content,
      filtersSnapshot = None,
      createdAt = "2026-08-11T12:05:00Z"
    )
    messages = messages :+ row
    row
  }

  override def listByConversation(conversationId: String): Seq[MessageRow] =
    messages.filter(_.conversationId == conversationId).sortBy(_.sequenceNumber)
}

class ConversationServiceSpec extends AnyFunSuite with Matchers {

  private def createFixture() = {
    val chatSessions = new FakeChatSessionRepo
    val conversations = new FakeConversationRepo
    val conversationStates = new FakeConversationStateRepo
    val messages = new FakeMessageRepo
    val service = new ConversationService(chatSessions, conversations, conversationStates, messages)
    (service, chatSessions, conversations, conversationStates, messages)
  }

  test("start creates a new chat session with conversationId = None") {
    val (service, _, _, _, _) = createFixture()
    val response = service.start("user-1")

    response.conversationId shouldBe None
    response.sessionId should startWith("session-")
    response.messages shouldBe empty
  }

  test("commitUserTurn returns 404 SESSION_NOT_FOUND for non-existent session") {
    val (service, _, _, _, _) = createFixture()
    val result = service.commitUserTurn("invalid-session", "user-1", "Hello")

    result shouldBe a[Left[_, _]]
    val failure = result.left.toOption.get
    failure.status shouldBe 404
    failure.code shouldBe Some("SESSION_NOT_FOUND")
  }

  test("commitUserTurn returns 403 FORBIDDEN for session owned by another user") {
    val (service, chatSessions, _, _, _) = createFixture()
    val session = chatSessions.insert("user-1")

    val result = service.commitUserTurn(session.id, "user-2", "Hello")

    result shouldBe a[Left[_, _]]
    val failure = result.left.toOption.get
    failure.status shouldBe 403
    failure.code shouldBe Some("FORBIDDEN")
  }

  test("commitUserTurn lazy-creates conversation and state on first message, then reuses it on second message") {
    val (service, chatSessions, conversations, conversationStates, messages) = createFixture()
    val session = chatSessions.insert("user-1")

    // Turn 1
    val result1 = service.commitUserTurn(session.id, "user-1", "Under $120 shoes")
    result1 shouldBe a[Right[_, _]]
    val turn1 = result1.toOption.get
    turn1.userMessage.content shouldBe "Under $120 shoes"
    turn1.userMessage.sequenceNumber shouldBe 1

    val convId = turn1.conversationId
    conversations.conversations should contain key convId
    conversationStates.states should contain key convId

    // Turn 2
    val result2 = service.commitUserTurn(session.id, "user-1", "Make them red")
    result2 shouldBe a[Right[_, _]]
    val turn2 = result2.toOption.get
    turn2.conversationId shouldBe convId
    turn2.userMessage.sequenceNumber shouldBe 2

    // Verify only ONE conversation was created
    conversations.conversations.size shouldBe 1
    messages.messages.size shouldBe 2
  }

  test("resume returns 404 for non-existent conversation") {
    val (service, _, _, _, _) = createFixture()
    val result = service.resume("invalid-conv", "user-1")

    result shouldBe a[Left[_, _]]
    result.left.toOption.get.status shouldBe 404
  }

  test("resume returns 403 when trying to resume another user's conversation") {
    val (service, _, conversations, _, _) = createFixture()
    val conv = conversations.insert("user-1")

    val result = service.resume(conv.id, "user-2")

    result shouldBe a[Left[_, _]]
    result.left.toOption.get.status shouldBe 403
  }

  test("resume creates a new session handle and returns conversation history") {
    val (service, chatSessions, conversations, _, messages) = createFixture()
    val conv = conversations.insert("user-1")
    messages.insertUserMessage(conv.id, "First message")

    val result = service.resume(conv.id, "user-1")

    result shouldBe a[Right[_, _]]
    val response = result.toOption.get
    response.conversationId shouldBe conv.id
    response.sessionId should startWith("session-")
    response.messages should have length 1
    response.messages.head.content shouldBe "First message"
  }

  test("rename updates title when owned by user, 404/403 otherwise") {
    val (service, _, conversations, _, _) = createFixture()
    val conv1 = conversations.insert("user-1")

    // 404
    service.rename("invalid-id", "user-1", RenameConversationRequest("New Title")).left.toOption.get.status shouldBe 404

    // 403
    service.rename(conv1.id, "user-2", RenameConversationRequest("New Title")).left.toOption.get.status shouldBe 403

    // 200
    val okResult = service.rename(conv1.id, "user-1", RenameConversationRequest("New Title"))
    okResult shouldBe a[Right[_, _]]
    okResult.toOption.get.title shouldBe Some("New Title")
  }

  test("delete removes conversation when owned by user, 404/403 otherwise") {
    val (service, _, conversations, _, _) = createFixture()
    val conv1 = conversations.insert("user-1")

    // 404
    service.delete("invalid-id", "user-1").left.toOption.get.status shouldBe 404

    // 403
    service.delete(conv1.id, "user-2").left.toOption.get.status shouldBe 403

    // 200
    val okResult = service.delete(conv1.id, "user-1")
    okResult shouldBe Right(())
    conversations.conversations should not contain key (conv1.id)
  }
}
