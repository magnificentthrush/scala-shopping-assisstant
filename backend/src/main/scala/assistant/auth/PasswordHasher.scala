package assistant.auth

import org.mindrot.jbcrypt.BCrypt

/** Wraps bcrypt so the rest of the codebase never touches a raw hashing
  * library directly. database-schema.md requires `password_hash` to be a
  * salted Argon2-or-bcrypt hash — bcrypt is what we use here. `BCrypt.gensalt()`
  * generates a fresh random salt per call, so two users with the same
  * password never get the same hash.
  */
object PasswordHasher {

  /** Work factor 12 is a reasonable default in 2026: slow enough to resist
    * brute force, fast enough not to make login noticeably slow.
    */
  private val WorkFactor = 12

  def hash(plainPassword: String): String =
    BCrypt.hashpw(plainPassword, BCrypt.gensalt(WorkFactor))

  def verify(plainPassword: String, storedHash: String): Boolean =
    try BCrypt.checkpw(plainPassword, storedHash)
    catch {
      // A malformed stored hash should never crash a login attempt with a
      // 500 — treat it as "password did not match".
      case _: IllegalArgumentException => false
    }
}
