package assistant.auth

import java.util.Base64
import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.time.Instant
import assistant.logging.Logger

/** A minimal HS256 JSON Web Token implementation.
  *
  * Why hand-rolled instead of a library (e.g. jwt-scala): the only two
  * primitives a JWT needs are (1) HMAC-SHA256, which is built into the JDK
  * via `javax.crypto`, and (2) base64url encoding, also built in. Writing
  * ~60 lines here avoids pulling in a third-party JWT library and its own
  * transitive dependency graph for something this small — and it doubles as
  * a good way to actually understand what a JWT *is* (three base64url
  * segments — header, payload, signature — joined by dots) rather than
  * treating it as a black box.
  *
  * Token shape: `base64url(header).base64url(payload).base64url(signature)`
  * where `signature = HMAC-SHA256(secret, header + "." + payload)`.
  */
object Jwt {
  private val Algorithm = "HmacSHA256"

  private def base64UrlEncode(bytes: Array[Byte]): String =
    Base64.getUrlEncoder.withoutPadding().encodeToString(bytes)

  private def base64UrlDecode(s: String): Array[Byte] =
    Base64.getUrlDecoder.decode(s)

  private def hmac(secret: String, data: String): Array[Byte] = {
    val mac = Mac.getInstance(Algorithm)
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), Algorithm))
    mac.doFinal(data.getBytes(StandardCharsets.UTF_8))
  }

  /** Issues a token for `userId`, valid for `ttlSeconds` (default 7 days —
    * generous for a university demo; the doc explicitly scopes refresh
    * tokens out of MVP, so a single long-lived access token is the whole
    * auth story for now).
    */
  def issue(secret: String, userId: String, ttlSeconds: Long = 7L * 24 * 3600): String = {
    val header = ujson.Obj("alg" -> "HS256", "typ" -> "JWT")
    val now = Instant.now().getEpochSecond
    val payload = ujson.Obj(
      "sub" -> userId,
      "iat" -> now,
      "exp" -> (now + ttlSeconds)
    )
    val headerB64 = base64UrlEncode(ujson.write(header).getBytes(StandardCharsets.UTF_8))
    val payloadB64 = base64UrlEncode(ujson.write(payload).getBytes(StandardCharsets.UTF_8))
    val signingInput = s"$headerB64.$payloadB64"
    val signature = base64UrlEncode(hmac(secret, signingInput))
    s"$signingInput.$signature"
  }

  /** Verifies signature and expiry, returns the user id (`sub`) on success.
    * Every failure mode (bad shape, bad signature, expired, unparsable
    * payload) collapses to `None` — callers must fail closed, exactly like
    * Gemma Call #1 must in the chat pipeline.
    */
  def verify(secret: String, token: String): Option[String] = {
    try {
      val parts = token.split("\\.")
      if (parts.length != 3) return None
      val Array(headerB64, payloadB64, signatureB64) = parts

      val expectedSig = base64UrlEncode(hmac(secret, s"$headerB64.$payloadB64"))
      if (!constantTimeEquals(expectedSig, signatureB64)) {
        Logger.security("JWT verification failed: signature mismatch")
        return None
      }

      val payload = ujson.read(new String(base64UrlDecode(payloadB64), StandardCharsets.UTF_8))
      val exp = payload("exp").num.toLong
      if (Instant.now().getEpochSecond > exp) {
        Logger.security("JWT verification failed: token expired")
        return None
      }
      Some(payload("sub").str)
    } catch {
      case e: Exception =>
        Logger.security(s"JWT verification failed: malformed token (${e.getClass.getSimpleName})")
        None
    }
  }

  /** Avoids timing attacks on signature comparison. */
  private def constantTimeEquals(a: String, b: String): Boolean = {
    if (a.length != b.length) return false
    var result = 0
    for (i <- a.indices) result |= a(i) ^ b(i)
    result == 0
  }
}
