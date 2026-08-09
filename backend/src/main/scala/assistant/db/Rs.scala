package assistant.db

import java.sql.ResultSet

object Rs {
  def optString(rs: ResultSet, column: String): Option[String] =
    Option(rs.getString(column))

  def optBoolean(rs: ResultSet, column: String): Option[Boolean] = {
    val value = rs.getBoolean(column)
    if (rs.wasNull()) None else Some(value)
  }

  def optDouble(rs: ResultSet, column: String): Option[Double] = {
    val value = rs.getDouble(column)
    if (rs.wasNull()) None else Some(value)
  }

  def optInt(rs: ResultSet, column: String): Option[Int] = {
    val value = rs.getInt(column)
    if (rs.wasNull()) None else Some(value)
  }
}