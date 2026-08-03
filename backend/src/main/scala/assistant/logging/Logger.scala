package assistant.logging

object Logger {
  def security(message: String): Unit = println(s"[SECURITY] $message")
  def error(message: String, cause: Option[Throwable] = None): Unit = {
    val suffix = cause.map(c => s" :: ${c.getMessage}").getOrElse("")
    println(s"[ERROR] $message$suffix")
  }
}
