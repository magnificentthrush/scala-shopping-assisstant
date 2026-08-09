package assistant.http

import assistant.auth.rateLimited
import assistant.domain.{LoginRequest, RegisterRequest}
import assistant.services.{AuthFailure, AuthService}
import upickle.default.{read, writeJs}

/** Thin HTTP layer for auth (docs/authPlan.md §7 step 13).
  *
  * Parses the request, calls `AuthService`, maps `Either` → status + JSON.
  * No password / JWT / DB logic here. `@rateLimited()` guards register and
  * login. `OPTIONS` handlers exist so browser CORS preflight can succeed
  * (the `Cors` main decorator then attaches the Allow-* headers).
  *
  * Uses `@cask.post` (not `postJson`) so we can return `Response.Raw` with
  * explicit status codes (201/401/403/409/429) matching the API contract.
  */
case class AuthRoutes(auth: AuthService)(implicit
    cc: castor.Context,
    log: cask.Logger
) extends cask.Routes {

  // --- CORS preflight (must be declared; decorators alone are not enough) ---

  @cask.route("/api/auth/register", methods = Seq("options"))
  def registerOptions(): cask.Response.Raw = noContent()

  @cask.route("/api/auth/login", methods = Seq("options"))
  def loginOptions(): cask.Response.Raw = noContent()

  @cask.route("/api/auth/verify-email", methods = Seq("options"))
  def verifyEmailOptions(): cask.Response.Raw = noContent()

  // --- Auth endpoints ---

  @rateLimited()
  @cask.post("/api/auth/register")
  def register(request: cask.Request): cask.Response.Raw =
    parseBody[RegisterRequest](request) match {
      case Left(failure) => errorJson(failure)
      case Right(req) =>
        auth.register(req) match {
          case Left(failure) => errorJson(failure)
          case Right(result) =>
            val body = ujson.Obj(
              "user" -> writeJs(result.user),
              "needsVerification" -> result.needsVerification
            )
            result.verificationToken.foreach(token => body("verificationToken") = token)
            json(201, body)
        }
    }

  @cask.get("/api/auth/verify-email")
  def verifyEmail(token: String = ""): cask.Response.Raw =
    auth.verifyEmail(token) match {
      case Left(failure)  => errorJson(failure)
      case Right(result) => json(200, ujson.Obj("verified" -> result.verified))
    }

  @rateLimited()
  @cask.post("/api/auth/login")
  def login(request: cask.Request): cask.Response.Raw =
    parseBody[LoginRequest](request) match {
      case Left(failure) => errorJson(failure)
      case Right(req) =>
        auth.login(req) match {
          case Left(failure) => errorJson(failure)
          case Right(result) =>
            json(
              200,
              ujson.Obj(
                "user" -> writeJs(result.user),
                "token" -> result.token
              )
            )
        }
    }

  private def parseBody[T: upickle.default.Reader](
      request: cask.Request
  ): Either[AuthFailure, T] =
    try Right(read[T](request.text()))
    catch {
      case _: Exception =>
        Left(
          AuthFailure(
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
  private def errorJson(failure: AuthFailure): cask.Response.Raw = {
    val body = ujson.Obj("error" -> failure.error)
    failure.code.foreach(code => body("code") = code)
    json(failure.status, body)
  }

  initialize()
}
