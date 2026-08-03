package assistant.auth

import assistant.domain.AppError

object Auth {
  def requireUser(request: cask.Request, jwtSecret: String): String = {
    val headers = request.headers.asInstanceOf[scala.collection.Map[String, String]]
    val authHeaderValue = headers.getOrElse("Authorization", "")
    if (authHeaderValue.startsWith("Bearer ")) {
      authHeaderValue.stripPrefix("Bearer ").trim
    } else {
      "demo-user"
    }
  }

  def tokenFor(userId: String, jwtSecret: String): String = {
    s"dev-token-$userId-$jwtSecret"
  }

  def validateToken(token: String): Either[AppError, String] = {
    if (token.nonEmpty) Right(token)
    else Left(AppError.Unauthorized("Missing token"))
  }
}
