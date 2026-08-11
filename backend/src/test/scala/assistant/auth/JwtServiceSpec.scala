package assistant.auth

import java.time.Clock

import assistant.config.AppConfig
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import pdi.jwt.{Jwt, JwtAlgorithm, JwtClaim}

/** Unit tests for JWT issue/verify (docs/authPlan.md §4, §7 step 10).
  *
  * No network — just the signing/verification round trip plus the failure
  * modes `AuthService`'s callers map to `401 UNAUTHORIZED` (bad signature,
  * expired, malformed, missing claims).
  */
class JwtServiceSpec extends AnyFunSuite with Matchers {

  private val secret = "unit-test-secret-that-is-long-enough"
  private val otherSecret = "a-different-secret-for-negative-tests"

  private val config = AppConfig(
    jwtSecret = secret,
    jwtExpiresInHours = 168L,
    supabaseUrl = "https://example.supabase.co",
    supabaseKey = "test-key",
    resendApiKey = "",
    emailFrom = "noreply@test.dev",
    frontendUrl = "http://localhost:5173",
    gemmaApiKey = "test-gemma-key"
  )

  private val service = new JwtService(config)

  private implicit val clock: Clock = Clock.systemUTC

  test("issue then verify recovers userId and email") {
    val token = service.issue("user-123", "ada@example.com")
    val payload = service.verify(token)

    payload shouldBe defined
    payload.get.userId shouldBe "user-123"
    payload.get.email shouldBe "ada@example.com"
  }

  test("token decodes to the expected HS256 claims (sub, email, iat, exp)") {
    val token = service.issue("user-123", "ada@example.com")
    val claim = Jwt.decode(token, secret, Seq(JwtAlgorithm.HS256)).toOption.get

    claim.subject shouldBe Some("user-123")
    claim.content should include(""" "email":"ada@example.com" """.trim)
    claim.issuedAt shouldBe defined
    claim.expiration shouldBe defined
    // 7 days (168h) lifetime per authPlan.md §2 — iat + 7d == exp.
    val iat = claim.issuedAt.get
    val exp = claim.expiration.get
    exp shouldBe (iat + config.jwtExpiresInHours * 3600L)
  }

  test("verify returns None for a token signed with a different secret") {
    val forged = Jwt
      .encode(
        JwtClaim(content = """{"email":"ada@example.com"}""").about("user-123").issuedNow,
        otherSecret,
        JwtAlgorithm.HS256
      )
    service.verify(forged) shouldBe None
  }

  test("verify returns None for an expired token") {
    val expired = Jwt
      .encode(
        JwtClaim(content = """{"email":"ada@example.com"}""")
          .about("user-123")
          .issuedNow
          .expiresIn(-3600L),
        secret,
        JwtAlgorithm.HS256
      )
    service.verify(expired) shouldBe None
  }

  test("verify returns None for garbage / malformed input") {
    service.verify("not.a.jwt") shouldBe None
    service.verify("") shouldBe None
    service.verify("a.b.c") shouldBe None
  }

  test("verify returns None when the email claim is missing") {
    val noEmail = Jwt.encode(JwtClaim().about("user-123").issuedNow, secret, JwtAlgorithm.HS256)
    service.verify(noEmail) shouldBe None
  }
}
