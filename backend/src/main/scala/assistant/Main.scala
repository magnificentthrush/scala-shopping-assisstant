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

  // Prefer localhost for host-machine browsers; Cask binds 0.0.0.0 inside Docker.
  val publicUrl = sys.env.getOrElse("BACKEND_URL", s"http://localhost:$port")
  println(s"")
  println(s"  ShopPilot backend ready")
  println(s"  ➜  Local:   $publicUrl")
  println(s"  ➜  Health:  $publicUrl/health")
  println(s"")
}
