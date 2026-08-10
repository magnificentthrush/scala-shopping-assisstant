package assistant.http

import assistant.domain.AppError

/** Every route method's body is wrapped in `handle { ... }`. This is the
  * cross-cutting error handling described in the mentor brief: instead of
  * every single route re-writing its own try/catch, one helper here
  * guarantees that:
  *
  *   - a thrown `AppError` (from `Auth.requireUser`, or from a service that
  *     throws instead of returning `Either`) becomes the correct
  *     `{error, code}` JSON + status code, via `ErrorMapping`.
  *   - any other exception becomes a generic logged 500, never a raw stack
  *     trace leaking to the client.
  *
  * Route methods that call a service returning `Either[AppError, A]`
  * still pattern-match that explicitly (so the happy path stays readable),
  * but wrap the whole method body in `handle` to catch `Auth.requireUser`'s
  * thrown `Unauthorized` and any unexpected exception.
  */
trait RouteSupport {
  def handle(body: => cask.Response[ujson.Value]): cask.Response[ujson.Value] =
    try body
    catch {
      case e: AppError => ErrorMapping.toResponse(e)
      case e: Throwable => ErrorMapping.unexpected(e)
    }

  /** Turns an `Either[AppError, A]` from a service into a response, given
    * a function to build the success response. Keeps route bodies to a
    * single expression instead of a manual match in every method.
    */
  def respond[A](result: Either[AppError, A])(onSuccess: A => cask.Response[ujson.Value]): cask.Response[ujson.Value] =
    result match {
      case Right(a)    => onSuccess(a)
      case Left(error) => ErrorMapping.toResponse(error)
    }

  def ok(body: ujson.Value, status: Int = 200): cask.Response[ujson.Value] =
    cask.Response(body, statusCode = status, headers = Cors.headers)
}

/** Minimal CORS support: React frontend runs on a different origin
  * (`FRONTEND_URL`), so every response needs `Access-Control-Allow-*`
  * headers, and every path needs to answer `OPTIONS` preflight requests
  * (browsers send these automatically before any request carrying an
  * `Authorization` header). `Origin: *` is safe here because auth is a
  * bearer JWT, not a cookie — no credentialed request relies on the
  * origin check.
  */
object Cors {
  val headers: Seq[(String, String)] = Seq(
    "Content-Type" -> "application/json",
    "Access-Control-Allow-Origin" -> "*",
    "Access-Control-Allow-Headers" -> "Content-Type, Authorization",
    "Access-Control-Allow-Methods" -> "GET, POST, PATCH, DELETE, OPTIONS"
  )
}
