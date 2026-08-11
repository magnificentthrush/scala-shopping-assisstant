package assistant.http

import assistant.auth.authed
import assistant.auth.JwtService
import assistant.domain.{SendMessageRequest, ValidationFailure, ValidationPassResponse}
import assistant.services.{ConversationService, MessageValidationService}
import upickle.default.{read, writeJs}

/** Thin HTTP layer for the chat message endpoint (docs/conversationPlan.md
  * §7, §8 task 10). Parses the body, runs the regex pre-filter → Call #1
  * (`MessageValidationService`), then commits the approved message
  * (`ConversationService.commitUserTurn`) and returns the §6 extended
  * pass body — the same `Either`→status+JSON pattern as `AuthRoutes`.
  *
  * `POST /api/sessions/{sessionId}/messages`: blank/regex/unsafe → `400` or
  * `422 REJECTED` (nothing written); ownership failures from
  * `commitUserTurn` → `403 FORBIDDEN` / `404 SESSION_NOT_FOUND`; success →
  * `200` with `conversationId` + the persisted `userMessage`. A valid JWT is
  * required via `@authed`.
  */
case class MessageRoutes(
    jwt: JwtService,
    validation: MessageValidationService,
    conversations: ConversationService
)(implicit
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
        validation.validate(req.message) match {
          case Left(failure) => errorJson(failure)
          case Right(_) =>
            conversations.commitUserTurn(sessionId, userId, req.message) match {
              case Left(failure) => errorJson(failure)
              case Right(turn) =>
                json(
                  200,
                  writeJs(
                    ValidationPassResponse(
                      safe = true,
                      sessionId = sessionId,
                      conversationId = turn.conversationId,
                      message = req.message,
                      userMessage = turn.userMessage
                    )
                  )
                )
            }
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
