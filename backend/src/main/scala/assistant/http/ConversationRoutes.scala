package assistant.http

import assistant.auth.authed
import assistant.auth.JwtService
import assistant.domain.{RenameConversationRequest, ValidationFailure}
import assistant.services.ConversationService
import upickle.default.{read, writeJs}

/** Thin HTTP layer for the five conversation CRUD routes
  * (docs/conversationPlan.md §8 task 11). Parses the request, calls
  * `ConversationService`, maps `Either` → status + JSON — the same pattern
  * as `AuthRoutes`/`MessageRoutes`. No ownership/DB logic here.
  *
  * Every route is `@authed` and injects `userId` from the JWT `sub`; the
  * service compares it against the conversation's `user_id` (403/404). Each
  * route also has an `OPTIONS` handler so browser CORS preflight can succeed.
  */
case class ConversationRoutes(jwt: JwtService, conversations: ConversationService)(implicit
    cc: castor.Context,
    log: cask.Logger
) extends cask.Routes {

  // --- CORS preflight (must be declared; decorators alone are not enough) ---
  // One OPTIONS handler per distinct path — `/api/conversations` covers
  // POST + GET, `/api/conversations/:conversationId` covers the collection
  // verbs. Cask keys routes by path + method, so a second handler on the
  // same pair would collide.

  @cask.route("/api/conversations", methods = Seq("options"))
  def listOptions(): cask.Response.Raw = noContent()

  @cask.route("/api/conversations/:conversationId", methods = Seq("options"))
  def conversationOptions(conversationId: String): cask.Response.Raw = noContent()

  @cask.route("/api/conversations/:conversationId/resume", methods = Seq("options"))
  def resumeOptions(conversationId: String): cask.Response.Raw = noContent()

  // --- Conversation endpoints ---

  @authed(jwt)
  @cask.post("/api/conversations")
  def start()(userId: String): cask.Response.Raw =
    json(201, writeJs(conversations.start(userId)))

  @authed(jwt)
  @cask.get("/api/conversations")
  def list()(userId: String): cask.Response.Raw =
    json(200, writeJs(conversations.list(userId)))

  @authed(jwt)
  @cask.post("/api/conversations/:conversationId/resume")
  def resume(conversationId: String)(userId: String): cask.Response.Raw =
    conversations.resume(conversationId, userId) match {
      case Left(failure)  => errorJson(failure)
      case Right(response) => json(200, writeJs(response))
    }

  @authed(jwt)
  @cask.patch("/api/conversations/:conversationId")
  def rename(conversationId: String, request: cask.Request)(userId: String): cask.Response.Raw =
    parseBody[RenameConversationRequest](request) match {
      case Left(failure) => errorJson(failure)
      case Right(req) =>
        conversations.rename(conversationId, userId, req) match {
          case Left(failure)  => errorJson(failure)
          case Right(summary) => json(200, writeJs(summary))
        }
    }

  @authed(jwt)
  @cask.delete("/api/conversations/:conversationId")
  def delete(conversationId: String)(userId: String): cask.Response.Raw =
    conversations.delete(conversationId, userId) match {
      case Left(failure) => errorJson(failure)
      case Right(())     => noContent()
    }

  private def parseBody[T: upickle.default.Reader](  // T = RenameConversationRequest
      request: cask.Request
  ): Either[ValidationFailure, T] =
    try Right(read[T](request.text()))
    catch {
      case _: Exception =>
        Left(
          ValidationFailure(
            status = 400,
            error = "Invalid JSON body",
            code = None
          )
        )
    }

  private def noContent(): cask.Response.Raw =
    cask.Response("", statusCode = 204)

  private def json(status: Int, body: ujson.Value): cask.Response.Raw =
    cask.Response(body, statusCode = status)

  /** Build error JSON without writing `"code": null` when absent. */
  private def errorJson(failure: ValidationFailure): cask.Response.Raw = {
    val body = ujson.Obj("error" -> failure.error)
    failure.code.foreach(code => body("code") = code)
    json(failure.status, body)
  }

  initialize()
}
