package assistant.repo

import assistant.domain.User
import assistant.domain.NullableOption.nullableOptionRW
import upickle.default._

/** Only DB access for the `users` table lives here — no password hashing,
  * no JWTs, no email sending. `AuthService` (docs/authPlan.md §7 step 11)
  * orchestrates those; this repo just reads/writes rows.
  */
class UserRepo(client: SupabaseRestClient) {
  private val Table: String = "users"

  /** Emails are matched case-insensitively (docs/authPlan.md §6), but
    * PostgREST's case-insensitive filter (`ilike`) treats `_` and `%` as
    * SQL `LIKE` wildcards — an email containing `_` (a valid, common
    * character) would then wrongly match other addresses too. Normalizing
    * to lowercase here and always filtering with the plain `eq` operator
    * avoids that trap entirely; callers never need to think about casing.
    */
  private def normalizeEmail(email: String): String = email.trim.toLowerCase

  def findByEmail(email: String): Option[User] = {
    val json = client.get(Table, Map("email" -> s"eq.${normalizeEmail(email)}"))
    read[Seq[User]](json).headOption
  }

  /** Looked up by hash only — deliberately does **not** filter out expired
    * tokens at the DB level. `AuthService` needs to tell "no such token"
    * (`TOKEN_INVALID`) apart from "found, but expired" (`TOKEN_EXPIRED,`
    * per authPlan.md §6), which requires seeing the row's
    * `verificationTokenExpiresAt` even when it's in the past.
    */
  def findByVerificationTokenHash(tokenHash: String): Option[User] = {
    val json = client.get(Table, Map("verification_token_hash" -> s"eq.$tokenHash"))
    read[Seq[User]](json).headOption
  }

  /** Inserts a new user row and returns it with DB-generated fields
    * filled in (`id`, `createdAt`, `updatedAt`; `emailVerified` defaults
    * to `false` per migration `002`/`007`). Uses a dedicated insert-only
    * shape rather than `User` itself, since those generated/defaulted
    * columns must not be sent in the request body.
    */
  def insert(
      fullName: String,
      email: String,
      passwordHash: String,
      verificationTokenHash: Option[String],
      verificationTokenExpiresAt: Option[String]
  ): User = {
    val row = UserRepo.NewUserRow(
      fullName = fullName,
      email = normalizeEmail(email),
      passwordHash = passwordHash,
      verificationTokenHash = verificationTokenHash,
      verificationTokenExpiresAt = verificationTokenExpiresAt
    )
    val json = client.post(Table, write(row))
    read[Seq[User]](json).headOption.getOrElse(
      throw new RuntimeException(s"Supabase returned no row after inserting user $email")
    )
  }

  /** Marks the user verified and clears the token columns so the
    * verification link becomes single-use (docs/authPlan.md §6, step 3 of
    * `GET /api/auth/verify-email`'s logic).
    */
  def markVerified(userId: String): Unit = {
    val patchBody = ujson.Obj(
      "email_verified" -> true,
      "verification_token_hash" -> ujson.Null,
      "verification_token_expires_at" -> ujson.Null
    )
    val json = client.patch(Table, Map("id" -> s"eq.$userId"), patchBody.toString)
    if (read[Seq[User]](json).isEmpty) {
      throw new RuntimeException(s"markVerified matched no user with id $userId")
    }
  }
}

object UserRepo {
  private case class NewUserRow(
      @upickle.implicits.key("full_name") fullName: String,
      email: String,
      @upickle.implicits.key("password_hash") passwordHash: String,
      @upickle.implicits.key("verification_token_hash") verificationTokenHash: Option[String],
      @upickle.implicits.key("verification_token_expires_at") verificationTokenExpiresAt: Option[
        String
      ]
  )

  private object NewUserRow {
    implicit val rw: ReadWriter[NewUserRow] = macroRW
  }
}
