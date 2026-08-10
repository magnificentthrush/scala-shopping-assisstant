package assistant.config

import scala.util.Try

/** Central place to read auth-related environment variables (see
  * docs/authPlan.md §7 step 5). Every other auth file takes an `AppConfig`
  * instead of calling `sys.env` directly, so tests can construct one by
  * hand instead of depending on process environment variables.
  *
  * `RESEND_API_KEY` is optional: blank means Phase 1 (no real email;
  * `emailEnabled` is false — see docs/authPlan.md §1). `JWT_SECRET`,
  * `SUPABASE_URL`, and `SUPABASE_KEY` are required and fail fast if unset.
  */
final case class AppConfig(
    jwtSecret: String,
    jwtExpiresInHours: Long,
    supabaseUrl: String,
    supabaseKey: String,
    resendApiKey: String,
    emailFrom: String,
    frontendUrl: String
) {

  /** True once Resend is configured. `AuthService` uses this to pick
    * `ResendEmailService` vs. `NoOpEmailService` and to decide whether
    * `register`'s response includes the raw `verificationToken` field
    * (Phase 1 fallback — see docs/authPlan.md §1 "Phased email delivery").
    */
  def emailEnabled: Boolean = resendApiKey.trim.nonEmpty
}

object AppConfig {
  private val DefaultJwtExpiresInHours = 168L // 7 days — see docs/authPlan.md §2
  private val DefaultEmailFrom = "noreply@scalainterns.dev"
  private val DefaultFrontendUrl = "http://localhost:5173"

  private def env(name: String): String = sys.env.getOrElse(name, "")

  private def required(name: String): String = {
    val value = env(name)
    if (value.trim.isEmpty) sys.error(s"Set $name in the environment (see .env.example)")
    value
  }

  /** Reads every auth-related env var once at startup. Call this from
    * `Main.scala` and pass the result down — don't call `fromEnv()` from
    * multiple places, or tests lose the ability to inject a fake config.
    */
  def fromEnv(): AppConfig = AppConfig(
    jwtSecret = required("JWT_SECRET"),
    jwtExpiresInHours =
      Try(env("JWT_EXPIRES_IN_HOURS").trim.toLong).getOrElse(DefaultJwtExpiresInHours),
    supabaseUrl = required("SUPABASE_URL"),
    supabaseKey = required("SUPABASE_KEY"),
    resendApiKey = env("RESEND_API_KEY"),
    emailFrom = {
      val value = env("EMAIL_FROM").trim
      if (value.isEmpty) DefaultEmailFrom else value
    },
    frontendUrl = {
      val value = env("FRONTEND_URL").trim
      if (value.isEmpty) DefaultFrontendUrl else value
    }
  )
}
