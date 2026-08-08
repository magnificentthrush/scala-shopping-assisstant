package assistant.auth

import assistant.domain.ErrorBody
import upickle.default.write

/** Cask decorator that requires a valid `Authorization: Bearer <jwt>`
  * header (docs/authPlan.md §4, §7 step 12).
  *
  * On success, injects `userId: String` (the JWT `sub`) into the handler's
  * last parameter list — the same decorator future conversation/session
  * routes will use for ownership checks.
  *
  * On failure (missing header, bad signature, expired, malformed) →
  * `401 { "error": "...", "code": "UNAUTHORIZED" }`. Never logs the token.
  *
  * Usage (step 13+):
  * {{{
  *   @authed(jwt)
  *   @cask.get("/api/example")
  *   def example()(userId: String) = ...
  * }}}
  */
class authed(jwt: JwtService) extends cask.RawDecorator {
  def wrapFunction(ctx: cask.Request, delegate: Delegate) =
    bearerToken(ctx) match {
      case Some(token) =>
        jwt.verify(token) match {
          case Some(payload) =>
            delegate(Map("userId" -> payload.userId))
          case None =>
            unauthorized("Invalid or expired token")
        }
      case None =>
        unauthorized("Missing or invalid Authorization header")
    }

  private def bearerToken(ctx: cask.Request): Option[String] =
    ctx.headers
      .get("authorization")
      .flatMap(_.headOption)
      .flatMap { header =>
        val prefix = "Bearer "
        if (header.regionMatches(true, 0, prefix, 0, prefix.length))
          Some(header.substring(prefix.length).trim).filter(_.nonEmpty)
        else None
      }

  private def unauthorized(message: String): cask.router.Result[cask.Response.Raw] = {
    val body: cask.Response.Raw = cask.Response(
      data = write(ErrorBody(error = message, code = Some("UNAUTHORIZED"))),
      statusCode = 401,
      headers = Seq("Content-Type" -> "application/json")
    )
    cask.router.Result.Success(body)
  }
}
