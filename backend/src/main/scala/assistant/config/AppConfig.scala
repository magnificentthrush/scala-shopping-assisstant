package assistant.config

/** Central place that reads every environment variable the app needs.
  *
  * Why this exists as its own file: nothing else in the codebase should call
  * `sys.env` directly. If we ever need to add a new setting, change a
  * default, or (later) load from a config file instead of env vars, there is
  * exactly one place to touch. Every other class receives an `AppConfig`
  * instance instead of reading the environment itself — that also makes
  * services trivially testable, since a test can build an `AppConfig` by
  * hand instead of mutating real env vars.
  */
final case class AppConfig(
    // Direct Postgres connection string, e.g.
    //   postgresql://user:password@host:5432/postgres
    // Supabase project settings -> Database -> Connection string -> URI.
    // NOTE: the project docs describe SUPABASE_URL/SUPABASE_KEY as the app's
    // client credentials (Supabase's REST/PostgREST API) and reserve
    // SUPABASE_DB_URL for the migration runner only. This backend instead
    // talks to Postgres directly over JDBC (the `org.postgresql` driver is
    // already a project dependency), which is simpler and more idiomatic
    // for a JVM backend than hand-rolling a PostgREST client. Point
    // SUPABASE_DB_URL at the same hosted Supabase database either way —
    // nothing about the schema or the hosting model changes.
    databaseUrl: String,
    gemmaApiKey: String,
    jwtSecret: String,
    frontendUrl: String,
    backendUrl: String,
    logLevel: String,
    llmLogging: Boolean
)

object AppConfig {

  /** Reads a required env var or fails fast at startup. We would rather
    * crash immediately with a clear message than run for an hour and then
    * NPE deep inside a repository class.
    */
  private def require(name: String): String =
    sys.env.getOrElse(
      name,
      throw new IllegalStateException(
        s"Missing required environment variable: $name. Check your .env file."
      )
    )

  private def optional(name: String, default: String): String =
    sys.env.getOrElse(name, default)

  def load(): AppConfig = AppConfig(
    databaseUrl = sys.env.getOrElse("SUPABASE_DB_URL", require("SUPABASE_URL")),
    gemmaApiKey = require("GEMMA_API_KEY"),
    jwtSecret = require("JWT_SECRET"),
    frontendUrl = optional("FRONTEND_URL", "http://localhost:5173"),
    backendUrl = optional("BACKEND_URL", "http://localhost:8080"),
    logLevel = optional("LOG_LEVEL", "INFO"),
    llmLogging = optional("LLM_LOGGING", "true").toBoolean
  )
}
