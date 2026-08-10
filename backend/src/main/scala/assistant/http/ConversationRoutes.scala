package assistant.http

import assistant.auth.Auth
import assistant.http.Dto._
import assistant.services.ConversationService
import upickle.default._

/** Route -> Service -> Repository -> Supabase, for `/api/conversations`.
  * Every method's first line is `Auth.requireUser` — no route here trusts
  * anything about "who is asking" other than what the verified JWT says.
  */
class ConversationRoutes(conversationService: ConversationService, jwtSecret: String)
    extends cask.Routes
    with RouteSupport {

  /** POST /api/conversations — start a new chat.
    * Response: 201 { conversationId, sessionId, title: null, messages: [] }
    */
  @cask.post("/api/conversations")
  def create(request: cask.Request): cask.Response[ujson.Value] = handle {
    val userId = Auth.requireUser(request, jwtSecret)
    val result = conversationService.startNewChat(userId)
    ok(
      writeJs(
        NewChatResponse(
          conversationId = result.conversation.id,
          sessionId = result.session.id,
          title = result.conversation.title,
          messages = Nil
        )
      ),
      status = 201
    )
  }

  /** GET /api/conversations — sidebar list, ordered by `lastMessageAt`
    * descending, scoped to the authenticated user only.
    */
  @cask.get("/api/conversations")
  def list(request: cask.Request): cask.Response[ujson.Value] = handle {
    val userId = Auth.requireUser(request, jwtSecret)
    val conversations = conversationService.listForUser(userId).map(ConversationSummaryDto.from)
    ok(writeJs(ConversationsListResponse(conversations)))
  }

  /** POST /api/conversations/:id/resume — open a past conversation.
    * Mints a NEW sessionId against the SAME conversation; history/state
    * are untouched. 404 if it doesn't exist, 403 if it isn't the caller's.
    */
  @cask.post("/api/conversations/:id/resume")
  def resume(id: String, request: cask.Request): cask.Response[ujson.Value] = handle {
    val userId = Auth.requireUser(request, jwtSecret)
    respond(conversationService.resume(id, userId)) { result =>
      val messages = result.messages.map(m => MessageDto.from(m)) // historical replay: no per-message product list
      ok(
        writeJs(
          ResumeResponse(
            conversationId = result.conversation.id,
            sessionId = result.session.id,
            title = result.conversation.title,
            messages = messages
          )
        )
      )
    }
  }

  /** PATCH /api/conversations/:id — rename. `{ title }` in, updated summary
    * out. Ownership is enforced inside the UPDATE's WHERE clause.
    */
  @cask.patch("/api/conversations/:id")
  def rename(id: String, request: cask.Request): cask.Response[ujson.Value] = handle {
    val userId = Auth.requireUser(request, jwtSecret)
    val req = read[RenameRequest](request.text())
    respond(conversationService.rename(id, userId, req.title))(conv => ok(writeJs(ConversationSummaryDto.from(conv))))
  }

  /** DELETE /api/conversations/:id — hard delete, `ON DELETE CASCADE`
    * removes messages/state/sessions at the DB level. 204 on success,
    * matching API_CONTRACT.md.
    */
  @cask.delete("/api/conversations/:id")
  def delete(id: String, request: cask.Request): cask.Response[ujson.Value] = handle {
    val userId = Auth.requireUser(request, jwtSecret)
    respond(conversationService.delete(id, userId))(_ => ok(ujson.Obj(), status = 204))
  }

  initialize()
}
