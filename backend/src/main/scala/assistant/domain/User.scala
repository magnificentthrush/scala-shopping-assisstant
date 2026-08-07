package assistant.domain

import upickle.default._
import upickle.implicits.key
import assistant.domain.NullableOption.nullableOptionRW

/** The `users` row as Supabase's PostgREST returns/expects it (see
  * docs/database-schema.md §"users"). Field names are camelCase in Scala;
  * `@key` maps each one to the snake_case column PostgREST uses on the
  * wire, so `UserRepo` (docs/authPlan.md §7 step 8) can read/write this
  * shape directly without a separate translation layer.
  *
  * `passwordHash` and the verification-token fields must never be exposed
  * to the frontend — see `AuthUserResponse` for the public-safe subset
  * that routes actually return.
  */
case class User(
    id: String,
    @key("full_name") fullName: String,
    email: String,
    @key("password_hash") passwordHash: String,
    @key("email_verified") emailVerified: Boolean,
    @key("verification_token_hash") verificationTokenHash: Option[String],
    @key("verification_token_expires_at") verificationTokenExpiresAt: Option[String],
    @key("created_at") createdAt: String,
    @key("updated_at") updatedAt: String
)

object User {
  implicit val rw: ReadWriter[User] = macroRW
}

/** `POST /api/auth/register` request body (docs/API_CONTRACT.md §Auth). */
case class RegisterRequest(fullName: String, email: String, password: String)

object RegisterRequest {
  implicit val rw: ReadWriter[RegisterRequest] = macroRW
}

/** `POST /api/auth/login` request body (docs/API_CONTRACT.md §Auth). */
case class LoginRequest(email: String, password: String)

object LoginRequest {
  implicit val rw: ReadWriter[LoginRequest] = macroRW
}

/** The public-safe `user` object embedded in auth responses — mirrors the
  * `User` TypeScript type in docs/API_CONTRACT.md. Deliberately excludes
  * `passwordHash` and the verification-token fields on `User` above.
  */
case class AuthUserResponse(id: String, fullName: String, email: String)

object AuthUserResponse {
  implicit val rw: ReadWriter[AuthUserResponse] = macroRW

  def fromUser(user: User): AuthUserResponse =
    AuthUserResponse(id = user.id, fullName = user.fullName, email = user.email)
}

/** Generic error shape used across the API, not just auth — mirrors the
  * `ErrorBody` TypeScript type in docs/API_CONTRACT.md. `code` is omitted
  * from the JSON (not written as `null`) when absent, matching the
  * optional `code?: string` on the frontend type.
  */
case class ErrorBody(error: String, code: Option[String] = None)

object ErrorBody {
  implicit val rw: ReadWriter[ErrorBody] = macroRW
}
