package assistant.services

/** Cheap, instant check for obvious prompt-injection phrases, run BEFORE
  * any LLM call.
  *
  * Reject-on-match only: this filter never modifies or strips a message.
  */
object RegexPreFilter {

  private val denylist: List[String] = List(
    """(?i)ignore\s+(all\s+)?previous\s+instructions""",
    """(?i)reveal\s+(the\s+)?system\s+prompt""",
    """(?i)you\s+are\s+now\s+"""
  )

  /** true = message matches a known attack pattern and must be rejected. */
  def isBlocked(message: String): Boolean =
    denylist.exists(pattern => message.matches(s".*$pattern.*"))
}