package assistant.services

import scala.util.matching.Regex

/** Stage 1 of the two-stage security pipeline (ARCHITECTURE.md §6):
  * a cheap, local regex denylist that runs BEFORE any LLM call.
  *
  * Two hard rules from the doc, both enforced by how this class is
  * written:
  *
  *   1. "Reject-on-match, never strip-and-continue." There is no method
  *      here that returns a "cleaned" string — only `check`, which returns
  *      pass/reject. A partially-stripped injection can still be
  *      effective, so we never try to sanitize and forward.
  *
  *   2. "The denylist is narrow and pattern-specific" — NOT bare words like
  *      "ignore" or "system", which false-positive on real shopping
  *      messages such as "ignore the mesh ones, I need waterproof leather".
  *      Every pattern below targets a specific instruction-injection
  *      *phrase*, not a topic word.
  */
object SecurityFilter {

  sealed trait Result
  case object Pass extends Result
  final case class Reject(reason: String) extends Result

  // (pattern, human-readable reason for logs — never shown to the user).
  private val denylist: List[(Regex, String)] = List(
    "ignore\\s+(all\\s+)?(previous|prior|above)\\s+instructions".r -> "instruction override attempt",
    "disregard\\s+(all\\s+)?(previous|prior|above)\\s+instructions".r -> "instruction override attempt",
    "reveal\\s+(the\\s+)?system\\s+prompt".r -> "system prompt exfiltration attempt",
    "show\\s+me\\s+(the\\s+)?system\\s+prompt".r -> "system prompt exfiltration attempt",
    "what\\s+(is|are)\\s+your\\s+(system\\s+)?instructions".r -> "system prompt exfiltration attempt",
    "you\\s+are\\s+now\\s+(a|an)\\s".r -> "role-override / jailbreak attempt",
    "act\\s+as\\s+(if\\s+you\\s+are\\s+)?(a|an)\\s+(unfiltered|unrestricted|jailbroken)".r -> "jailbreak attempt",
    "pretend\\s+(you\\s+have\\s+)?no\\s+(restrictions|rules|guidelines)".r -> "jailbreak attempt",
    "developer\\s+mode".r -> "jailbreak attempt (DAN-style)",
    "\\bdo\\s+anything\\s+now\\b".r -> "jailbreak attempt (DAN-style)"
  )

  def check(message: String): Result = {
    val normalized = message.toLowerCase
    denylist.collectFirst { case (pattern, reason) if pattern.findFirstIn(normalized).isDefined => reason } match {
      case Some(reason) => Reject(reason)
      case None          => Pass
    }
  }
}
