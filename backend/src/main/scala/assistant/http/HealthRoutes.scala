package assistant.http

/** GET /health — no auth, no DB call. Used by Docker Compose healthchecks
  * and manual "is the backend up" checks. Deliberately does not touch the
  * database, so it stays fast and can't itself become a failure point.
  */
class HealthRoutes() extends cask.Routes {
  @cask.get("/health")
  def health(): ujson.Value = ujson.Obj("status" -> "ok")

  initialize()
}
