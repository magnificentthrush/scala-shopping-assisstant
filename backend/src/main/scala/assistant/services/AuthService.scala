package assistant.services

import java.nio.charset.StandardCharsets
import java.security.{MessageDigest, SecureRandom}
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64

import assistant.auth.{JwtService, PasswordHasher}
import assistant.config.AppConfig
import assistant.domain.{AuthUserResponse, LoginRequest, RegisterRequest}
import assistant.repo.UserRepo

/** Orchestrates register / verify-email / login (docs/authPlan.md §6, §7
  * step 11). Controllers stay thin — they parse the HTTP request, call
  * one of these methods, and turn the `Either` into a status + JSON body.
  *
  * Dependencies are constructor-injected so tests can swap in fakes
  * without touching env vars or the real Supabase project.
  */
class AuthService(
    config: AppConfig,
    users: UserRepo,
    emails: EmailService,
    jwt: JwtService
) {
  private val secureRandom = new SecureRandom
  private val emailPattern = "^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$".r
  private val VerificationTokenTtlHours = 24L

  def register(req: RegisterRequest): Either[AuthFailure, RegisterResult] =
    for {
      _ <- validateRegister(req)
      _ <- users.findByEmail(req.email) match {
        case Some(_) =>
          Left(
            AuthFailure(
              status = 409,
              error = "Email already registered",
              code = Some("EMAIL_TAKEN")
            )
          )
        case None => Right(())
      }
    } yield {
      val passwordHash = PasswordHasher.hash(req.password)
      val (rawToken, tokenHash) = newVerificationToken()
      val expiresAt = Instant.now().plus(VerificationTokenTtlHours, ChronoUnit.HOURS).toString
      val user = users.insert(
        fullName = req.fullName.trim,
        email = req.email,
        passwordHash = passwordHash,
        verificationTokenHash = Some(tokenHash), 
        verificationTokenExpiresAt = Some(expiresAt)
      )
      val link = s"${config.frontendUrl.stripSuffix("/")}/verify-email?token=$rawToken"
      // Always call the trait — NoOp vs Resend is decided at wiring time,
      // not here (docs/authPlan.md §1 / §6 step 6).
      emails.sendVerificationEmail(user.email, link)
      RegisterResult(
        user = AuthUserResponse.fromUser(user),
        needsVerification = true,
        // Phase 1 only: when Resend isn't configured, hand the raw token
        // back so the frontend can verify without an inbox.
        verificationToken = if (config.emailEnabled) None else Some(rawToken)
      )
    }

  def verifyEmail(rawToken: String): Either[AuthFailure, VerifyResult] = {
    val trimmed = Option(rawToken).map(_.trim).getOrElse("")
    if (trimmed.isEmpty) {
      Left(tokenInvalid)
    } else {
      users.findByVerificationTokenHash(sha256Hex(trimmed)) match { //defined in userRepo, return Option[user]
        case None => Left(tokenInvalid)
        case Some(user) =>
          user.verificationTokenExpiresAt match { //verificationTokenExpiresAt is Option[String]
            case None => Left(tokenInvalid)
            case Some(expiresAtRaw) =>
              parseInstant(expiresAtRaw) match { //parseinstant defined below return Option[date]
                case None => Left(tokenInvalid)
                case Some(expiresAt) if expiresAt.isBefore(Instant.now()) => 
                  Left(
                    AuthFailure(
                      status = 400,
                      error = "This verification link has expired.",
                      code = Some("TOKEN_EXPIRED")
                    )
                  )
                case Some(_) =>
                  users.markVerified(user.id)
                  Right(
                    VerifyResult(
                      verified = true
                      )
                    )
              }
          }
      }
    }
  }

  def login(req: LoginRequest): Either[AuthFailure, LoginResult] = {
    val invalid =
      AuthFailure(
        status = 401,
        error = "Invalid email or password",
        code = Some("INVALID_CREDENTIALS")
      )

    // Same generic failure for "no such user" and "wrong password" so we
    // don't leak which emails are registered (docs/authPlan.md §6).
    users.findByEmail(req.email) match {
      case None => Left(invalid)
      case Some(user) =>
        if (!PasswordHasher.verify(req.password, user.passwordHash)) Left(invalid)
        else if (!user.emailVerified)
          Left(
            AuthFailure(
              status = 403,
              error = "Please verify your email before logging in.",
              code = Some("EMAIL_NOT_VERIFIED")
            )
          )
        else
          Right(
            LoginResult(
              user = AuthUserResponse.fromUser(user),
              token = jwt.issue(user.id, user.email)
            )
          )
    }
  }

  //run fullname, email and password checks just like frontend
  private def validateRegister(req: RegisterRequest): Either[AuthFailure, Unit] = {
    val fullName = req.fullName.trim
    val email = req.email.trim
    if (fullName.isEmpty)
      Left(AuthFailure(400, "Full name is required", None))
    else if (email.isEmpty || emailPattern.findFirstIn(email).isEmpty)
      Left(AuthFailure(400, "Please enter a valid email address", None))
    else if (!isValidPassword(req.password))
      Left(
        AuthFailure(
          400,
          "Password must be at least 8 characters and include 1 digit and 1 uppercase letter",
          None
        )
      )
    else Right(())
  }

  /** Same rule as docs/authPlan.md §5 — enforced here independently of
    * the frontend checklist. */
  private def isValidPassword(password: String): Boolean =
    password.length >= 8 &&
      password.exists(_.isDigit) &&
      password.exists(_.isUpper)

  private def newVerificationToken(): (String /* raw */, String /* sha256 hex */) = {
    val bytes = new Array[Byte](32)
    secureRandom.nextBytes(bytes) //nextBytes method that fills a byte array with random values.
    val raw_token = Base64.getUrlEncoder.withoutPadding.encodeToString(bytes) //getURLEncoder is not normal Base64 Encoding
    (raw_token, sha256Hex(raw_token))
  }

  private def sha256Hex(value: String): String = {
    val digest =
      MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))
    digest.map("%02x".format(_)).mkString
  }

  private def parseInstant(value: String): Option[Instant] =
    try Some(Instant.parse(value))
    catch { case _: Exception => None }

  private def tokenInvalid: AuthFailure =
    AuthFailure(
      status = 400,
      error = "This verification link is invalid or has expired.", 
      code = Some("TOKEN_INVALID")
    )
}

/** Failure returned by `AuthService` — routes map `status`/`error`/`code`
  * straight onto the HTTP response / `ErrorBody`.
  */
final case class AuthFailure(status: Int, error: String, code: Option[String])

final case class RegisterResult(
    user: AuthUserResponse,
    needsVerification: Boolean,
    verificationToken: Option[String]
)

final case class VerifyResult(verified: Boolean)

final case class LoginResult(user: AuthUserResponse, token: String)
