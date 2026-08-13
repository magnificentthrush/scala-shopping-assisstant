package assistant.repo

import assistant.config.AppConfig
import sttp.client3._

/** The one place sttp/HTTP details for talking to Supabase's REST API
  * (PostgREST) live — table-specific repos like `UserRepo` only think in
  * terms of table names, filters, and JSON bodies, never headers or URLs
  * (docs/authPlan.md §2, §7 step 8; matches the established pattern in
  * `docs/project-plan.md` §5: "Supabase client for app queries, direct
  * Postgres connection only for migrations").
  *
  * Uses `HttpClientSyncBackend`, sttp's synchronous backend built on
  * Java 11+'s native `HttpClient` — the rest of this codebase is
  * synchronous too (e.g. `GeminiLLMClient` blocks on the Gen AI SDK
  * directly), so there's no `Future`/async story to fit into yet.
  * Deliberately not `HttpURLConnectionBackend`: verified empirically (via
  * a throwaway `runMain` script against the real Supabase project, then
  * deleted) that it throws `ProtocolException: Invalid HTTP method:
  * PATCH` — `java.net.HttpURLConnection` only allows a fixed method
  * whitelist that excludes `PATCH`, and sttp's usual reflection-based
  * workaround for that doesn't work under this JDK's module
  * encapsulation. `HttpClientSyncBackend` supports `PATCH` natively.
  */
class SupabaseRestClient(config: AppConfig) {
  private val backend = HttpClientSyncBackend()
  private val baseUrl = s"${config.supabaseUrl}/rest/v1"

  // API key + bearer token required on every PostgREST request.
  private def authHeaders: Map[String, String] = Map(
    "apikey" -> config.supabaseKey,
    "Authorization" -> s"Bearer ${config.supabaseKey}"
  )

  /** `GET {table}?{params}` — `params` are PostgREST filters, e.g.
    * `Map("email" -> "eq.ada@example.com")`. PostgREST always responds
    * with a JSON array, even for zero or one matching row.
    */
  def get(table: String, params: Map[String, String]): String = {
    val uri = uri"$baseUrl/$table?$params"
    val response = basicRequest.headers(authHeaders).get(uri).send(backend)
    bodyOrThrow(response, s"GET $table")
  }

  /** `POST {table}` with a JSON array/object body — inserts row(s).
    * `Prefer: return=representation` makes PostgREST echo the inserted
    * row(s) back as the response body instead of an empty `201`, so
    * callers get DB-generated fields (`id`, `created_at`, ...) in one
    * round trip.
    */
  def post(table: String, jsonBody: String): String = {
    val uri = uri"$baseUrl/$table"
    val response = basicRequest
      .headers(authHeaders ++ Map("Prefer" -> "return=representation"))
      .post(uri)
      .body(jsonBody)
      .contentType("application/json")
      .send(backend)
    bodyOrThrow(response, s"POST $table")
  }

  /** `PATCH {table}?{params}` with a JSON body — updates matching row(s).
    * Same `Prefer: return=representation` convention as `post`.
    */
  def patch(table: String, params: Map[String, String], jsonBody: String): String = {
    val uri = uri"$baseUrl/$table?$params"
    val response = basicRequest
      .headers(authHeaders ++ Map("Prefer" -> "return=representation"))
      .patch(uri)
      .body(jsonBody)
      .contentType("application/json")
      .send(backend)
    bodyOrThrow(response, s"PATCH $table")
  }

  /** `DELETE {table}?{params}` — deletes matching row(s). `params` are
    * PostgREST filters, e.g. `Map("id" -> s"eq.$id")`. `Prefer:
    * return=representation` makes PostgREST echo the deleted row(s) back as
    * a JSON array (empty if none matched), so `ConversationRepo.delete` can
    * tell "deleted something" from "matched nothing" — same convention as
    * `post`/`patch`.
    */
  def delete(table: String, params: Map[String, String]): String = {
    val uri = uri"$baseUrl/$table?$params"
    val response = basicRequest
      .headers(authHeaders ++ Map("Prefer" -> "return=representation"))
      .delete(uri)
      .send(backend)
    bodyOrThrow(response, s"DELETE $table")
  }

  /** `POST rpc/{name}` with a JSON object body — invokes a Postgres function
    * via PostgREST RPC.
    */
  def rpc(name: String, jsonBody: String): String = {
    val uri = uri"$baseUrl/rpc/$name"
    val response = basicRequest
      .headers(authHeaders)
      .post(uri)
      .body(jsonBody)
      .contentType("application/json")
      .send(backend)
    bodyOrThrow(response, s"RPC $name")
  }

  // Unwraps a 2xx body, or throws with the HTTP status and error payload.
  private def bodyOrThrow(response: Response[Either[String, String]], context: String): String =
    response.body match {
      case Right(body) => body
      case Left(errorBody) =>
        throw new RuntimeException(
          s"Supabase REST call failed ($context, status ${response.code}): $errorBody"
        )
    }
}
