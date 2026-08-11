package assistant.http

import assistant.auth.authed
import assistant.auth.JwtService
import assistant.domain.{SendMessageRequest, ValidationFailure}
import assistant.services.MessageValidationService
import upickle.default.{read, writeJs}

/** Thin HTTP layer for the chat message endpoint (docs/call1Plan.md §7
  * step 7). Parses the body, calls `MessageValidationService`, maps the
  * `Either` to status + JSON — the same pattern as `AuthRoutes`.
  *
  * `POST /api/sessions/{sessionId}/messages` runs the regex pre-filter →
  * Call #1 (`PromptValidator`) and returns either the temporary
  * `ValidationPassResponse` stub (`200`) or `422 REJECTED`. This pass
  * `sessionId` is echoed from the path and is **not** looked up (no
  * persistence / ownership yet). A valid JWT is required via `@authed`.
  */
case class MessageRoutes(jwt: JwtService, validation: MessageValidationService)(implicit
    cc: castor.Context,
    log: cask.Logger
) extends cask.Routes {

  // --- CORS preflight (must be declared; decorators alone are not enough) ---

  @cask.route("/api/sessions/:sessionId/messages", methods = Seq("options"))
  def sendMessageOptions(sessionId: String): cask.Response.Raw = noContent()

  // --- Message endpoint ---

  @authed(jwt)
  @cask.post("/api/sessions/:sessionId/messages")
  def sendMessage(sessionId: String, request: cask.Request)(userId: String): cask.Response.Raw =
    parseBody[SendMessageRequest](request) match {
      case Left(failure) => errorJson(failure)
      case Right(req) =>
        validation.validate(req.message, sessionId) match {
          case Left(failure) => errorJson(failure)
          case Right(pass)   => json(200, writeJs(pass))
        }
    }

  private def parseBody[T: upickle.default.Reader](  // T = SendMessageRequest
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
