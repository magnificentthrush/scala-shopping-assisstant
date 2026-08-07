package assistant.services

import assistant.config.AppConfig
import sttp.client3._
import ujson._

/** Outbound verification-email seam (docs/authPlan.md §1 "Phased email
  * delivery", §7 step 9). `AuthService` only ever calls this trait — it
  * never branches on Resend vs. no-op itself for the send. Which
  * implementation is wired is decided once at startup via
  * `EmailService.fromConfig`.
  */
trait EmailService {

  /** Deliver (or, for `NoOpEmailService`, log) the verification link.
    * Implementations own their own failure mode: `ResendEmailService`
    * throws on a non-2xx from Resend so a send failure fails the
    * register call loudly rather than silently leaving the user with
    * no way to verify.
    */
  def sendVerificationEmail(to: String, link: String): Unit
}

object EmailService {

  /** Pick the concrete impl from `AppConfig.emailEnabled` — the single
    * switch documented in docs/authPlan.md §1. Call once at startup and
    * pass the result into `AuthService`; don't call this from multiple
    * places or tests lose the ability to inject a fake.
    */
  def fromConfig(config: AppConfig): EmailService =
    if (config.emailEnabled) new ResendEmailService(config)
    else new NoOpEmailService
}

/** Phase 1 fallback — used when `RESEND_API_KEY` is blank. Logs the
  * verification link server-side and sends nothing; the register
  * response itself carries the raw `verificationToken` so the frontend
  * can complete verification without an inbox (docs/authPlan.md §1).
  */
class NoOpEmailService extends EmailService {
  def sendVerificationEmail(to: String, link: String): Unit =
    println(s"[email] NoOpEmailService — would send verification link to $to: $link")
}

/** Phase 2 — posts to Resend's HTTP API (`POST https://api.resend.com/emails`)
  * using the same synchronous `HttpClientSyncBackend` as
  * `SupabaseRestClient` (see docs/authPlan.md §7 step 8 for why not
  * `HttpURLConnectionBackend`). From-address is `AppConfig.emailFrom`
  * (default `noreply@scalainterns.dev`).
  */
class ResendEmailService(config: AppConfig) extends EmailService {
  private val backend = HttpClientSyncBackend()
  private val endpoint = uri"https://api.resend.com/emails"

  def sendVerificationEmail(to: String, link: String): Unit = {
    val body = Obj(
      "from" -> config.emailFrom,
      "to" -> Arr(Str(to)),
      "subject" -> "Verify your ShopPilot email",
      "html" -> htmlBody(link),
      "text" -> s"Verify your ShopPilot email by opening this link: $link"
    ).render()

    val response = basicRequest
      .header("Authorization", s"Bearer ${config.resendApiKey}")
      .contentType("application/json")
      .post(endpoint)
      .body(body)
      .send(backend)

    response.body match {
      case Right(_) => ()
      case Left(errorBody) =>
        throw new RuntimeException(
          s"Resend email send failed (status ${response.code}): $errorBody"
        )
    }
  }

  private def htmlBody(link: String): String =
    s"""<p>Welcome to ShopPilot.</p>
       |<p><a href="$link">Click here to verify your email</a>.</p>
       |<p>If the link doesn't work, paste this URL into your browser:</p>
       |<p>$link</p>
       |<p>This link expires in 24 hours.</p>""".stripMargin
}
