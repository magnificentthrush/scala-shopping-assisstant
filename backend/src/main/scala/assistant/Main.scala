package assistant

object Main extends cask.MainRoutes {
  override def host: String = "0.0.0.0"
  override def port: Int = 8080

  @cask.get("/")
  def index(): ujson.Value =
    ujson.Obj(
      "message" -> "ShopPilot backend is running"
    )

  @cask.get("/health")
  def health(): ujson.Value =
    ujson.Obj(
      "status" -> "ok"
    )

  initialize()
}
