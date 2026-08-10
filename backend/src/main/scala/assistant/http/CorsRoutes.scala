package assistant.http

/** Handles CORS preflight (OPTIONS) requests for every real API path.
  * Explicit paths instead of a "/" wildcard — a wildcard subpath route
  * was conflicting with exact-path GET/POST routes and causing them to
  * return 405 instead of falling through to their real handler.
  */
class CorsRoutes() extends cask.Routes {

  @cask.options("/api/auth/register")
  def preflightRegister(): cask.Response[String] = preflight()

  @cask.options("/api/auth/login")
  def preflightLogin(): cask.Response[String] = preflight()

  @cask.options("/api/conversations")
  def preflightConversations(): cask.Response[String] = preflight()

  @cask.options("/api/conversations/:id")
  def preflightConversationById(id: String): cask.Response[String] = preflight()

  @cask.options("/api/conversations/:id/resume")
  def preflightResume(id: String): cask.Response[String] = preflight()

  @cask.options("/api/sessions/:sessionId/messages")
  def preflightMessages(sessionId: String): cask.Response[String] = preflight()

  @cask.options("/api/products")
  def preflightProducts(): cask.Response[String] = preflight()

  @cask.options("/me")
  def preflightMe(): cask.Response[String] = preflight()

  @cask.options("/logout")
  def preflightLogout(): cask.Response[String] = preflight()

  @cask.options("/api/profile")
  def preflightProfile(): cask.Response[String] = preflight()

  private def preflight(): cask.Response[String] =
    cask.Response("", statusCode = 204, headers = Cors.headers)

  initialize()
}