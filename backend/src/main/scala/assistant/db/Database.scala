package assistant.db

import java.sql.{Connection, DriverManager}
import java.net.{URI, URLDecoder}

class Database(rawUrl: String) {

  Class.forName("org.postgresql.Driver")

  private val jdbcUrl: String = {
    if (rawUrl.startsWith("jdbc:")) rawUrl
    else {
      val uri = new URI(rawUrl)
      val userInfo = Option(uri.getUserInfo).getOrElse("")
      val (user, password) = userInfo.split(":", 2) match {
        case Array(u, p) => (URLDecoder.decode(u, "UTF-8"), URLDecoder.decode(p, "UTF-8"))
        case Array(u)    => (URLDecoder.decode(u, "UTF-8"), "")
        case _           => ("", "")
      }
      val host = uri.getHost
      val port = if (uri.getPort > 0) uri.getPort else 5432
      val path = Option(uri.getPath).getOrElse("/postgres")
      s"jdbc:postgresql://$host:$port$path?user=$user&password=$password&sslmode=require"
    }
  }

  private def newConnection(): Connection = DriverManager.getConnection(jdbcUrl)

  def withConnection[T](f: Connection => T): T = {
    val conn = newConnection()
    try f(conn)
    finally conn.close()
  }

  def withTransaction[T](f: Connection => T): T = {
    val conn = newConnection()
    conn.setAutoCommit(false)
    try {
      val result = f(conn)
      conn.commit()
      result
    } catch {
      case e: Throwable =>
        conn.rollback()
        throw e
    } finally {
      conn.close()
    }
  }
}