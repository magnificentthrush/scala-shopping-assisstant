package assistant.http

/** Adds CORS headers to every matched response so the React app on
  * `FRONTEND_URL` can call this API from the browser (docs/authPlan.md
  * §7 step 13). Allowed headers include `Authorization` (JWT) and
  * `Content-Type` (JSON bodies).
  *
  * Preflight (`OPTIONS`) needs its own route handlers — Cask only runs
  * decorators on matched methods — see `AuthRoutes`.
  */
class Cors(allowedOrigin: String) extends cask.RawDecorator {
  private val origin = allowedOrigin.stripSuffix("/")

  private val corsHeaders: Seq[(String, String)] = Seq(
    "Access-Control-Allow-Origin" -> origin,
    "Access-Control-Allow-Headers" -> "Authorization, Content-Type",
    "Access-Control-Allow-Methods" -> "GET, POST, PUT, PATCH, DELETE, OPTIONS",
    "Access-Control-Max-Age" -> "86400"
  )

  def wrapFunction(ctx: cask.Request, delegate: Delegate) =  //delegate the original enpoint handler below @cask.*
    delegate(Map.empty) match {
      case cask.router.Result.Success(response) =>
        cask.router.Result.Success(
          response.copy(headers = response.headers ++ corsHeaders)
        )
      case other => other
    }
}
