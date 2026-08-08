package assistant.repo

import assistant.db.Database
import assistant.domain.User
import java.sql.ResultSet

/** All SQL for the `users` table lives here and nowhere else.
  *
  * This is the Repository Pattern: services (e.g. `AuthService`) never
  * write SQL or touch `java.sql` types directly — they call
  * `userRepo.findByEmail(...)` and get back a `Option[User]`. If we ever
  * moved off Postgres, or added caching, only this file would change.
  */
class UserRepo(db: Database) {

  private def fromRow(rs: ResultSet): User = User(
    id = rs.getString("id"),
    fullName = rs.getString("full_name"),
    email = rs.getString("email"),
    passwordHash = rs.getString("password_hash"),
    createdAt = rs.getTimestamp("created_at").toInstant,
    updatedAt = rs.getTimestamp("updated_at").toInstant
  )

  def findByEmail(email: String): Option[User] = db.withConnection { conn =>
    val stmt = conn.prepareStatement("SELECT * FROM users WHERE email = ?")
    stmt.setString(1, email)
    val rs = stmt.executeQuery()
    try if (rs.next()) Some(fromRow(rs)) else None
    finally { rs.close(); stmt.close() }
  }

  def findById(id: String): Option[User] = db.withConnection { conn =>
    val stmt = conn.prepareStatement("SELECT * FROM users WHERE id = ?")
    stmt.setString(1, id)
    val rs = stmt.executeQuery()
    try if (rs.next()) Some(fromRow(rs)) else None
    finally { rs.close(); stmt.close() }
  }

  /** Updates the caller's own display name. Used by `PATCH /api/profile`.
    * Email/password changes are deliberately out of scope for MVP (not in
    * database-schema.md or API_CONTRACT.md) — do not add them here without
    * updating those docs first, since email is the login identifier and
    * changing it has auth implications (re-verification, session
    * invalidation) that are a separate feature.
    */
  def updateFullName(id: String, fullName: String): Option[User] = db.withConnection { conn =>
    val stmt = conn.prepareStatement(
      "UPDATE users SET full_name = ?, updated_at = now() WHERE id = ? RETURNING *"
    )
    stmt.setString(1, fullName)
    stmt.setString(2, id)
    val rs = stmt.executeQuery()
    try if (rs.next()) Some(fromRow(rs)) else None
    finally { rs.close(); stmt.close() }
  }

  /** Inserts a new user. Relies on the table's `DEFAULT gen_random_uuid()`
    * and `DEFAULT now()` for id/timestamps — Postgres generates them, we
    * just read them back via RETURNING so the returned `User` is complete.
    *
    * Uniqueness of `email` is enforced by the DB's `UNIQUE` constraint;
    * `AuthService` is responsible for turning the resulting
    * `SQLException` (Postgres error code 23505) into a clean
    * `AppError.Conflict("EMAIL_TAKEN")` rather than a 500.
    */
  def insert(fullName: String, email: String, passwordHash: String): User = db.withConnection { conn =>
    val stmt = conn.prepareStatement(
      """INSERT INTO users (full_name, email, password_hash)
        |VALUES (?, ?, ?)
        |RETURNING *""".stripMargin
    )
    stmt.setString(1, fullName)
    stmt.setString(2, email)
    stmt.setString(3, passwordHash)
    val rs = stmt.executeQuery()
    try {
      rs.next()
      fromRow(rs)
    } finally { rs.close(); stmt.close() }
  }
}
