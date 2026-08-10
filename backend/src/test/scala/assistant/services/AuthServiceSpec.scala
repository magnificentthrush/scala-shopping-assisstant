package assistant.services

import java.util.UUID

import assistant.auth.{JwtService, PasswordHasher}
import assistant.config.AppConfig
import assistant.domain.{LoginRequest, RegisterRequest, User}
import assistant.repo.{SupabaseRestClient, UserRepo}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** Unit tests for `AuthService` orchestration (docs/authPlan.md §6, §7
  * step 11) — register / verify-email / login business logic against an
  * in-memory fake `UserRepo`. No network, no env vars: every dependency is
  * constructor-injected per the class's design comment.
  *
  * The real `PasswordHasher` and `JwtService` are used (not faked) so the
  * Argon2id round trip and HS256 sign/verify are exercised end to end.
  */
class AuthServiceSpec extends AnyFunSuite with Matchers {

  // --- Test fixtures ------------------------------------------------------

  private val testSecret = "auth-service-test-secret"

  private def config(emailEnabled: Boolean): AppConfig =
    AppConfig(
      jwtSecret = testSecret,
      jwtExpiresInHours = 168L,
      supabaseUrl = "https://example.supabase.co",
      supabaseKey = "test-key",
      resendApiKey = if (emailEnabled) "re_test_key" else "",
      emailFrom = "noreply@test.dev",
      frontendUrl = "http://localhost:5173"
    )

  private val phase1 = config(emailEnabled = false)
  private val phase2 = config(emailEnabled = true)

  /** In-memory `users` table that mirrors `UserRepo`'s contract:
    * case-insensitive email lookup, insert with DB-generated fields,
    * lookup by verification-token hash, and mark-verified clearing.
    */
  private class InMemoryUserRepo extends UserRepo(new SupabaseRestClient(phase1)) {
    var users: Vector[User] = Vector.empty

    override def findByEmail(email: String): Option[User] =
      users.find(_.email == email.trim.toLowerCase)

    override def findByVerificationTokenHash(tokenHash: String): Option[User] =
      users.find(_.verificationTokenHash.contains(tokenHash))

    override def insert(
        fullName: String,
        email: String,
        passwordHash: String,
        verificationTokenHash: Option[String],
        verificationTokenExpiresAt: Option[String]
    ): User = {
      val now = "2026-08-09T00:00:00.000000Z"
      val user = User(
        id = UUID.randomUUID().toString,
        fullName = fullName,
        email = email.trim.toLowerCase,
        passwordHash = passwordHash,
        emailVerified = false,
        verificationTokenHash = verificationTokenHash,
        verificationTokenExpiresAt = verificationTokenExpiresAt,
        createdAt = now,
        updatedAt = now
      )
      users = users :+ user
      user
    }

    override def markVerified(userId: String): Unit = {
      val idx = users.indexWhere(_.id == userId)
      if (idx < 0) throw new RuntimeException(s"markVerified matched no user with id $userId")
      val u = users(idx)
      users = users.updated(idx, u.copy(emailVerified = true, verificationTokenHash = None, verificationTokenExpiresAt = None))
    }
  }

  private def newService(
      repo: InMemoryUserRepo,
      config: AppConfig
  ): AuthService = {
    val emails = new RecordingEmailService
    val jwt = new JwtService(config)
    new AuthService(config, repo, emails, jwt)
  }

  private class NewServiceWithEmails(val service: AuthService, val emails: RecordingEmailService)

  private def newServiceWithEmails(
      repo: InMemoryUserRepo,
      config: AppConfig
  ): NewServiceWithEmails = {
    val emails = new RecordingEmailService
    val jwt = new JwtService(config)
    new NewServiceWithEmails(new AuthService(config, repo, emails, jwt), emails)
  }

  private class RecordingEmailService extends EmailService {
    var sent: Vector[(String, String)] = Vector.empty // (to, link)
    def sendVerificationEmail(to: String, link: String): Unit =
      sent = sent :+ (to, link)
  }

  private val validRegister = RegisterRequest(
    fullName = "Ada Lovelace",
    email = "Ada@Example.com", // deliberately mixed case
    password = "correct1Horse"
  )

  private def userRow(
      repo: InMemoryUserRepo,
      email: String,
      password: String,
      verified: Boolean = true
  ): User = {
    val u = repo.insert(
      fullName = "Ada Lovelace",
      email = email,
      passwordHash = PasswordHasher.hash(password),
      verificationTokenHash = Some("hash"),
      verificationTokenExpiresAt = Some("2099-01-01T00:00:00.000000Z")
    )
    if (verified) repo.markVerified(u.id)
    repo.users.find(_.id == u.id).get
  }

  // --- register -----------------------------------------------------------

  test("register succeeds and returns needsVerification with a Phase 1 token") {
    val repo = new InMemoryUserRepo
    val service = newService(repo, phase1)

    val result = service.register(validRegister)

    result shouldBe a[Right[_, _]]
    val register = result.toOption.get
    register.needsVerification shouldBe true
    register.verificationToken shouldBe defined // Phase 1: token comes back in JSON
    register.user.email shouldBe "ada@example.com" // normalized lowercase
    register.user.id should not be empty
  }

  test("register in Phase 2 does not include a verification token") {
    val repo = new InMemoryUserRepo
    val service = newService(repo, phase2)

    val result = service.register(validRegister)

    result.toOption.get.verificationToken shouldBe None
  }

  test("register rejects an empty fullName") {
    val repo = new InMemoryUserRepo
    val service = newService(repo, phase1)

    val result = service.register(validRegister.copy(fullName = "   "))

    result shouldBe a[Left[_, _]]
    val failure = result.left.toOption.get
    failure.status shouldBe 400
  }

  test("register rejects a malformed email") {
    val repo = new InMemoryUserRepo
    val service = newService(repo, phase1)

    val result = service.register(validRegister.copy(email = "not-an-email"))

    result.left.toOption.get.status shouldBe 400
  }

  test("register rejects a weak password") {
    val repo = new InMemoryUserRepo
    val service = newService(repo, phase1)

    val result = service.register(validRegister.copy(password = "short"))

    val failure = result.left.toOption.get
    failure.status shouldBe 400
    failure.error should include("8 characters")
  }

  test("register rejects a duplicate email (case-insensitive)") {
    val repo = new InMemoryUserRepo
    val service = newService(repo, phase1)
    service.register(validRegister) shouldBe a[Right[_, _]]

    val duplicate = service.register(RegisterRequest("Someone Else", "ADA@example.com", "otherPass1"))

    val failure = duplicate.left.toOption.get
    failure.status shouldBe 409
    failure.code shouldBe Some("EMAIL_TAKEN")
  }

  test("register sends the verification link to the email service") {
    val repo = new InMemoryUserRepo
    val wired = newServiceWithEmails(repo, phase1)

    val result = wired.service.register(validRegister)

    result shouldBe a[Right[_, _]]
    val register = result.toOption.get
    wired.emails.sent should have size 1
    val (to, link) = wired.emails.sent.head
    to shouldBe "ada@example.com"
    link should include(register.verificationToken.get) // link carries the raw token
    link should startWith("http://localhost:5173/verify-email?token=")
  }

  // --- verify-email -------------------------------------------------------

  test("verifyEmail succeeds and clears the token (single-use)") {
    val repo = new InMemoryUserRepo
    val service = newService(repo, phase1)
    val token = service.register(validRegister).toOption.get.verificationToken.get

    val result = service.verifyEmail(token)

    result shouldBe Right(assistant.services.VerifyResult(verified = true))
    val user = repo.users.head
    user.emailVerified shouldBe true
    user.verificationTokenHash shouldBe None
    user.verificationTokenExpiresAt shouldBe None
  }

  test("verifyEmail rejects a reused token after success") {
    val repo = new InMemoryUserRepo
    val service = newService(repo, phase1)
    val token = service.register(validRegister).toOption.get.verificationToken.get
    service.verifyEmail(token) shouldBe a[Right[_, _]]

    val reuse = service.verifyEmail(token)

    val failure = reuse.left.toOption.get
    failure.status shouldBe 400
    failure.code shouldBe Some("TOKEN_INVALID")
  }

  test("verifyEmail rejects an unknown token") {
    val repo = new InMemoryUserRepo
    val service = newService(repo, phase1)

    val result = service.verifyEmail("no-such-token")

    result.left.toOption.get.code shouldBe Some("TOKEN_INVALID")
  }

  test("verifyEmail rejects an empty token") {
    val repo = new InMemoryUserRepo
    val service = newService(repo, phase1)

    service.verifyEmail("").left.toOption.get.code shouldBe Some("TOKEN_INVALID")
  }

  test("verifyEmail reports TOKEN_EXPIRED when the stored token has expired") {
    val repo = new InMemoryUserRepo
    val service = newService(repo, phase1)
    val token = service.register(validRegister).toOption.get.verificationToken.get

    // Force the stored expiry for the registered row into the past.
    val u = repo.users.head
    repo.users = repo.users.updated(0, u.copy(verificationTokenExpiresAt = Some("2020-01-01T00:00:00.000000Z")))

    val result = service.verifyEmail(token)

    val failure = result.left.toOption.get
    failure.status shouldBe 400
    failure.code shouldBe Some("TOKEN_EXPIRED")
  }

  // --- login --------------------------------------------------------------

  test("login returns INVALID_CREDENTIALS for an unknown email") {
    val repo = new InMemoryUserRepo
    val service = newService(repo, phase1)

    val result = service.login(LoginRequest("nobody@example.com", "correct1Horse"))

    val failure = result.left.toOption.get
    failure.status shouldBe 401
    failure.code shouldBe Some("INVALID_CREDENTIALS")
  }

  test("login returns INVALID_CREDENTIALS for a wrong password") {
    val repo = new InMemoryUserRepo
    val service = newService(repo, phase1)
    userRow(repo, "ada@example.com", "correct1Horse")

    val result = service.login(LoginRequest("ada@example.com", "wrongPassword1"))

    result.left.toOption.get.code shouldBe Some("INVALID_CREDENTIALS")
  }

  test("login returns EMAIL_NOT_VERIFIED for an unverified account") {
    val repo = new InMemoryUserRepo
    val service = newService(repo, phase1)
    repo.insert(
      fullName = "Ada Lovelace",
      email = "ada@example.com",
      passwordHash = PasswordHasher.hash("correct1Horse"),
      verificationTokenHash = Some("hash"),
      verificationTokenExpiresAt = Some("2099-01-01T00:00:00.000000Z")
    ) // emailVerified defaults to false

    val result = service.login(LoginRequest("ada@example.com", "correct1Horse"))

    val failure = result.left.toOption.get
    failure.status shouldBe 403
    failure.code shouldBe Some("EMAIL_NOT_VERIFIED")
  }

  test("login issues a JWT with the expected claims after verification") {
    val repo = new InMemoryUserRepo
    val service = newService(repo, phase1)
    val user = userRow(repo, "ada@example.com", "correct1Horse")

    val result = service.login(LoginRequest("ADA@example.com", "correct1Horse"))

    result shouldBe a[Right[_, _]]
    val login = result.toOption.get
    login.user.email shouldBe "ada@example.com"
    login.user.id shouldBe user.id

    val jwt = new JwtService(phase1)
    val payload = jwt.verify(login.token)
    payload shouldBe defined
    payload.get.userId shouldBe user.id
    payload.get.email shouldBe "ada@example.com"
  }

  // --- password policy mirror --------------------------------------------------

  test("password policy matches the frontend checklist rule") {
    val repo = new InMemoryUserRepo
    val service = newService(repo, phase1)

    service.register(validRegister.copy(password = "abcdefgh")) shouldBe a[Left[_, _]] // no digit
    service.register(validRegister.copy(password = "abcdefg1")) shouldBe a[Left[_, _]] // no uppercase
    service.register(validRegister.copy(password = "Abcdefg1")) shouldBe a[Right[_, _]] // all rules met
  }
}
