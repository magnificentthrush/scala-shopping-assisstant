package assistant.auth

import assistant.domain.ErrorBody
import upickle.default.write

/**We are using "sliding window log algorithm" to store timestamps of each 
 * request by eaach IP and make sure that count of those requests do not go beyond
 * defaultMaxRequests = 10, and it takes good amount of memory but is most precise algo.
 * 
 * 
 *  Per-IP sliding-window rate limiter as a Cask decorator
  * (docs/authPlan.md §2, §7 step 12; ARCHITECTURE.md §3).
  *
  * In-memory on purpose: one Cask process, no extra infra. Each
  * `@rateLimited(...)` site owns its own counter store so `register`
  * and `login` are limited independently. If the backend ever runs
  * multiple replicas, replace `SlidingWindowCounter` with a shared
  * store — keep this decorator's HTTP contract the same.
  *
  * Defaults: 10 requests per 60 seconds per IP. Override via constructor
  * when applying the annotation.
  *
  * Over limit → `429 { "error": "...", "code": "RATE_LIMITED" }`.
  *
  * Usage (step 13):
  * {{{
  *   @rateLimited()
  *   @cask.postJson("/api/auth/login")
  *   def login(...) = ...
  * }}}
  */
class rateLimited(
    maxRequests: Int = rateLimited.DefaultMaxRequests,
    windowMs: Long = rateLimited.DefaultWindowMs
) extends cask.RawDecorator {

  private val counter = new SlidingWindowCounter(maxRequests, windowMs)

  // Every request hits wrapFunction first: allow → call the endpoint;
  // deny → HTTP 429 immediately.
  def wrapFunction(ctx: cask.Request, delegate: Delegate) = {
    val ip = rateLimited.clientIp(ctx)
    if (counter.tryAcquire(ip)) delegate(Map.empty)
    else {
      val body: cask.Response.Raw = cask.Response(
        data = write(
          ErrorBody(
            error = "Too many requests. Try again later.",
            code = Some("RATE_LIMITED")
          )
        ),
        statusCode = 429,
        headers = Seq("Content-Type" -> "application/json")
      )
      cask.router.Result.Success(body)
    }
  }
}

object rateLimited {
  val DefaultMaxRequests: Int = 10
  val DefaultWindowMs: Long = 60 * 1000L

  /** Client IP for rate limiting. Uses Undertow's peer address only —
    * never a client-supplied `X-Forwarded-For`, which is trivial to
    * spoof and would let an attacker bypass the limit. If a reverse
    * proxy is added later, have it pass the real peer via PROXY
    * protocol / trusted connection info, then teach this method that
    * peer — do not start trusting raw `X-Forwarded-For` from the client.
    */
  def clientIp(ctx: cask.Request): String =
    Option(ctx.exchange.getSourceAddress) // /192.168.1.25:54321
      .flatMap(addr => Option(addr.getAddress)) // 192.168.1.25
      .map(_.getHostAddress) //returns "192.168.1.25"
      .getOrElse("unknown") //return "unknown"
}

/** Thread-safe sliding-window counter keyed by an opaque string (usually
  * an IP). Extracted so it can be tested without standing up Cask.
  *
  * `tryAcquire` returns `true` and records the hit when under the limit,
  * or `false` when the key already has `maxRequests` timestamps inside
  * the current window (denied hits are not recorded). Expired timestamps
  * and idle keys are pruned so the map does not grow without bound.
  */
final class SlidingWindowCounter(maxRequests: Int, windowMs: Long) {
  require(maxRequests > 0, "maxRequests must be positive")
  require(windowMs > 0, "windowMs must be positive")

  private val lock = new Object
  private val hits = scala.collection.mutable.Map.empty[String, List[Long]]

  def tryAcquire(key: String): Boolean = lock.synchronized {    //can this IP make one more request? and lock.sync locks it for a request
    val now = System.currentTimeMillis()
    val cutoff = now - windowMs
    pruneExpired(cutoff)
    val recent = hits.getOrElse(key, Nil).filter(_ > cutoff) //get timestamp of key(ip) or Nil(empty list) and filter is repeated
    if (recent.size >= maxRequests) {
      hits(key) = recent
      false
    } else {
      hits(key) = now :: recent // [current timestamp] + [existing timestamps]
      true
    }
  }

  /** How many hits remain in the window for `key` (tests / diagnostics). */
  def count(key: String): Int = lock.synchronized {
    val cutoff = System.currentTimeMillis() - windowMs
    pruneExpired(cutoff)
    hits.getOrElse(key, Nil).count(_ > cutoff)
  }

  private def pruneExpired(cutoff: Long): Unit = {    //remove timestamps of each IP in hits(map) that are older than cutoff
    hits.keys.toList.foreach { key =>
      val kept = hits(key).filter(_ > cutoff)
      if (kept.isEmpty) hits.remove(key)
      else hits(key) = kept
    }
  }
}
