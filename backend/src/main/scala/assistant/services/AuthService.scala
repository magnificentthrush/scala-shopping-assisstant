package assistant.services

import assistant.auth.Auth
import assistant.domain.{AppError, User}
import assistant.repo.UserRepo

import java.time.Instant
import java.util.UUID

final case class AuthResult(user: User, token: String)

class AuthService(userRepo: UserRepo, jwtSecret: String) {
  def register(fullName: String, email: String, password: String): Either[AppError, AuthResult] = {
    if (fullName.trim.isEmpty) Left(AppError.BadRequest("fullName is required"))
    else if (email.trim.isEmpty) Left(AppError.BadRequest("email is required"))
    else if (password.trim.isEmpty) Left(AppError.BadRequest("password is required"))
    else if (userRepo.findByEmail(email).nonEmpty) Left(AppError.Conflict("EMAIL_TAKEN"))
    else {
      val userId = UUID.randomUUID().toString
      val user = User(userId, fullName.trim, email.trim.toLowerCase, s"hash:${password.trim}")
      userRepo.save(user)
      val token = Auth.tokenFor(userId, jwtSecret)
      Right(AuthResult(user, token))
    }
  }

  def login(email: String, password: String): Either[AppError, AuthResult] = {
    userRepo.findByEmail(email.trim.toLowerCase) match {
      case Some(user) if user.passwordHash == s"hash:${password.trim}" =>
        Right(AuthResult(user, Auth.tokenFor(user.id, jwtSecret)))
      case _ => Left(AppError.Unauthorized("INVALID_CREDENTIALS"))
    }
  }

  def me(userId: String): Either[AppError, User] = {
    userRepo.findById(userId) match {
      case Some(user) => Right(user)
      case None => Left(AppError.NotFound("User not found"))
    }
  }
}
