package assistant.http

/** Basic liveness endpoints kept separate from auth so `Main` can mount
  * several `cask.Routes` objects (docs/authPlan.md §7 step 13).
  */
case class HealthRoutes()(implicit
    cc: castor.Context,
    log: cask.Logger
) extends cask.Routes {

  @cask.get("/") //localhost:8080/
  def index(): ujson.Value =
    ujson.Obj("message" -> "ShopPilot backend is running")

  @cask.get("/health")
  def health(): ujson.Value =
    ujson.Obj("status" -> "ok")

  initialize()
}
