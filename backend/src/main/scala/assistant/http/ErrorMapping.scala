package assistant.http

import assistant.domain.AppError
import assistant.http.Dto.ErrorBody
import assistant.logging.Logger
import upickle.default._

/** Maps every `AppError` to exactly the `{ error, code }` shape and status
  * code documented in API_CONTRACT.md. This is the ONE place that
  * translation happens — every route's catch block calls
  * `ErrorMapping.toResponse`, so a new error case added to
  * `domain.Errors` automatically gets consistent handling everywhere
  * instead of each route re-implementing (and risking mismatched) status
  * codes.
  */
object ErrorMapping {

  def toResponse(error: AppError): cask.Response[ujson.Value] = {
    val status = error match {
      case _: AppError.BadRequest          => 400
      case _: AppError.Unauthorized        => 401
      case _: AppError.Forbidden           => 403
      case _: AppError.NotFound            => 404
      case _: AppError.Conflict            => 409
      case _: AppError.Rejected            => 422
      case _: AppError.AssistantFailed     => 500
      case _: AppError.UpstreamUnavailable => 503
      case _: AppError.Internal            => 500
    }
    val body = ErrorBody(error.message, Some(error.code))
    cask.Response(writeJs(body), statusCode = status, headers = Cors.headers)
  }

  /** Catches anything a route did NOT anticipate (a raw exception escaping
    * a repo/service) and turns it into a generic 500 instead of Cask's
    * default error page — while still logging the real cause server-side.
    * `AppError`s should be caught before this via `ErrorMapping.toResponse`
    * directly; this is the last-resort safety net.
    */
  def unexpected(e: Throwable): cask.Response[ujson.Value] = {
    Logger.error("Unhandled exception in route", Some(e))
    val body = ErrorBody("Internal server error", Some("INTERNAL_ERROR"))
    cask.Response(writeJs(body), statusCode = 500, headers = Cors.headers)
  }
}
