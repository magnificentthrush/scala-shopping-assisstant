package assistant.logging

import java.io.{FileWriter, PrintWriter}
import java.time.Instant
import java.nio.file.{Files, Paths}

/** Cross-cutting logging utility.
  *
  * ARCHITECTURE.md §7 is explicit that logging must be a shared
  * utility/middleware, not `println` scattered through auth, LLM calls, and
  * repositories. Every other class that wants to log calls into this single
  * object instead of writing files itself.
  *
  * Two files, matching the doc:
  *   - backend/logs/app.log   -> startup/shutdown, requests, auth events,
  *                                authz failures, DB errors, exceptions
  *   - backend/logs/error.log -> unexpected exceptions only
  *
  * CRITICAL RULE: never log a password, JWT, or Authorization header value,
  * at any level. Callers pass already-redacted strings; this object does not
  * try to guess what looks like a secret.
  */
object Logger {
  private val logsDir = "logs"
  Files.createDirectories(Paths.get(logsDir))

  private def timestamp(): String = Instant.now().toString

  private def writeLine(fileName: String, line: String): Unit = {
    val writer = new PrintWriter(new FileWriter(s"$logsDir/$fileName", true))
    try writer.println(line)
    finally writer.close()
  }

  def info(message: String): Unit = {
    val line = s"[${timestamp()}] [INFO] $message"
    println(line)
    writeLine("app.log", line)
  }

  def warn(message: String): Unit = {
    val line = s"[${timestamp()}] [WARN] $message"
    println(line)
    writeLine("app.log", line)
  }

  /** Authentication / authorization events: logins, failed logins, JWT
    * verification failures, ownership-check rejections (IDOR attempts).
    */
  def security(message: String): Unit = {
    val line = s"[${timestamp()}] [SECURITY] $message"
    println(line)
    writeLine("app.log", line)
  }

  def error(message: String, throwable: Option[Throwable] = None): Unit = {
    val stack = throwable.map(t => "\n" + t.getStackTrace.mkString("\n")).getOrElse("")
    val line = s"[${timestamp()}] [ERROR] $message${throwable.map(t => s" (${t.getClass.getName}: ${t.getMessage})").getOrElse("")}$stack"
    System.err.println(line)
    writeLine("app.log", line)
    writeLine("error.log", line)
  }
}
