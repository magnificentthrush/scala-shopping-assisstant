package assistant.http

/** Answers CORS preflight (`OPTIONS`) requests for every path, using Cask's
  * `subpath = true` wildcard matching so we don't need one `@cask.options`
  * per endpoint. Browsers send these automatically ahead of any request
  * carrying a custom header (our JWT's `Authorization` header) or a JSON
  * body — i.e. ahead of nearly every call this API receives.
  */
class CorsRoutes() extends cask.Routes {
  @cask.options("/", subpath = true)
  def preflight(): cask.Response[String] =
    cask.Response("", statusCode = 204, headers = Cors.headers)

  initialize()
}
