package assistant.auth

import java.time.Clock

import assistant.config.AppConfig
import pdi.jwt.{Jwt, JwtAlgorithm, JwtClaim}
import scala.util.Try
import ujson._

/** JWT issue/verify for auth (docs/authPlan.md §4, §7 step 10).
  *
  * Claims on the wire: `sub` (user id), `email`, `iat`, `exp`. Nothing
  * else — keep the token small and avoid putting mutable fields like
  * `fullName` in it (they can't be invalidated before `exp`).
  *
  * Uses `jwt-core` (not `jwt-upickle`) — see docs/authPlan.md §2 for the
  * upickle version conflict that forced that choice. Custom claim JSON
  * (`email`) is built/parsed with the project's existing `ujson`.
  *
  * Never log the token itself (docs/ARCHITECTURE.md §7).
  */
class JwtService(config: AppConfig) {
  // jwt-scala's issuedNow / expiresIn need a Clock; pin UTC so tokens
  // don't drift with the host's local timezone.
  private implicit val clock: Clock = Clock.systemUTC
  private val algorithm = JwtAlgorithm.HS256
  private val expiresInSeconds: Long = config.jwtExpiresInHours * 3600L

  /** Mint a signed HS256 token for a verified user. */
  def issue(userId: String, email: String): String = {
    val claim = JwtClaim(content = Obj("email" -> email).render())
      .about(userId)
      .issuedNow
      .expiresIn(expiresInSeconds)
    Jwt.encode(claim, config.jwtSecret, algorithm)
  }

  /** Validate signature + expiry and return the claims, or `None` on any
    * failure (bad signature, expired, malformed, missing `sub`/`email`).
    * Callers treat every failure the same — `401 UNAUTHORIZED` — so we
    * don't distinguish failure modes here.
    */
  def verify(token: String): Option[JwtPayload] =
    Jwt
      .decode(token, config.jwtSecret, Seq(algorithm))
      .toOption
      .flatMap(payloadFromClaim)

  private def payloadFromClaim(claim: JwtClaim): Option[JwtPayload] =
    for {
      userId <- claim.subject
      email <- Try(read(claim.content)("email").str).toOption
      if userId.nonEmpty && email.nonEmpty
    } yield JwtPayload(userId = userId, email = email)
}

/** The verified contents of a JWT — what `AuthedRoute` (step 12) will
  * inject into protected handlers. `userId` is the JWT `sub`.
  */
final case class JwtPayload(userId: String, email: String)
