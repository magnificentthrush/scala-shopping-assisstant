package assistant.auth

import assistant.domain.AppError
import assistant.logging.Logger

/** JWT Authentication Middleware.
  *
  * Cask does support decorator-based middleware (`@authenticated()` on top
  * of a route), but its decorator API shape has changed across Cask
  * versions and is easy to get subtly wrong. This project instead uses an
  * explicit function that every protected route calls as its first line:
  *
  *   val userId = Auth.requireUser(request, config.jwtSecret)
  *
  * This is one extra line per protected route, but it is version-safe,
  * IDE-navigable (you can jump straight to the check instead of following
  * an annotation), and — for a project whose whole point is teaching
  * backend fundamentals — it makes the "auth happens before anything else"
  * rule visible in the route body instead of hidden in framework magic.
  *
  * `requireUser` throws `AppError.Unauthorized`, which the route's
  * try/catch (see `http.ErrorMapping`) turns into the exact 401 JSON shape
  * from API_CONTRACT.md. Routes never construct that error response by
  * hand.
  */
object Auth {

  def requireUser(request: cask.Request, jwtSecret: String): String = {
    val token = extractBearerToken(request).getOrElse {
      Logger.security(s"Rejected ${request.exchange.getRequestPath}: missing Authorization header")
      throw AppError.Unauthorized("Missing Authorization header")
    }
    Jwt.verify(jwtSecret, token).getOrElse {
      Logger.security(s"Rejected ${request.exchange.getRequestPath}: invalid or expired token")
      throw AppError.Unauthorized("Invalid or expired token")
    }
  }

  private def extractBearerToken(request: cask.Request): Option[String] =
    request.headers.get("authorization").flatMap(_.headOption).collect {
      case h if h.startsWith("Bearer ") => h.substring("Bearer ".length).trim
    }
}
