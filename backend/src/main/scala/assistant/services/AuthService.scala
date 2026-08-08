package assistant.services

import assistant.auth.{Jwt, PasswordHasher}
import assistant.domain.{AppError, User}
import assistant.repo.UserRepo
import assistant.logging.Logger
import java.sql.SQLException

final case class AuthResult(user: User, token: String)

/** Business logic for `POST /api/auth/register` and `POST /api/auth/login`.
  * Routes call this; this calls `UserRepo`. Routes never touch
  * `PasswordHasher`, `Jwt`, or SQL directly — that separation is the whole
  * point of a service layer: the HTTP route only knows "give me an
  * AuthResult or an AppError", not how one gets produced.
  */
class AuthService(userRepo: UserRepo, jwtSecret: String) {

  private val EmailPattern = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$".r

  def register(fullName: String, email: String, password: String): Either[AppError, AuthResult] = {
    if (fullName.trim.isEmpty) return Left(AppError.BadRequest("Full name is required"))
    if (!EmailPattern.matches(email)) return Left(AppError.BadRequest("A valid email is required"))
    if (password.length < 8) return Left(AppError.BadRequest("Password must be at least 8 characters"))

    val normalizedEmail = email.trim.toLowerCase

    try {
      val hash = PasswordHasher.hash(password)
      val user = userRepo.insert(fullName.trim, normalizedEmail, hash)
      Logger.security(s"New user registered: userId=${user.id}")
      Right(AuthResult(user, Jwt.issue(jwtSecret, user.id)))
    } catch {
      case e: SQLException if e.getSQLState == "23505" => // unique_violation on email
        Left(AppError.Conflict("Email already registered", "EMAIL_TAKEN"))
    }
  }

  def login(email: String, password: String): Either[AppError, AuthResult] = {
    val normalizedEmail = email.trim.toLowerCase
    userRepo.findByEmail(normalizedEmail) match {
      case Some(user) if PasswordHasher.verify(password, user.passwordHash) =>
        Right(AuthResult(user, Jwt.issue(jwtSecret, user.id)))
      case Some(_) =>
        Logger.security(s"Failed login attempt: wrong password for email=$normalizedEmail")
        Left(AppError.Unauthorized("Invalid email or password", "INVALID_CREDENTIALS"))
      case None =>
        Logger.security(s"Failed login attempt: unknown email=$normalizedEmail")
        // Same error/message as a wrong password — never reveal whether an
        // email is registered, that would leak account existence.
        Left(AppError.Unauthorized("Invalid email or password", "INVALID_CREDENTIALS"))
    }
  }

  def me(userId: String): Either[AppError, User] =
    userRepo.findById(userId).toRight(AppError.NotFound("User not found"))
}
