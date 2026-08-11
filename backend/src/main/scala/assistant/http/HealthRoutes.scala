package assistant.http

/** Simple health checks, separate from auth/chat routes. */
class HealthRoutes() extends cask.Routes {

  @cask.get("/")
  def index(): ujson.Value =
    ujson.Obj("message" -> "ShopPilot backend is running")

  @cask.get("/health")
  def health(): ujson.Value =
    ujson.Obj("status" -> "ok")

  initialize()
}

object HealthRoutes {
  def apply(): HealthRoutes = new HealthRoutes()
}