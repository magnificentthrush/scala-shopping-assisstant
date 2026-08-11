package assistant.services

import java.util.regex.Pattern

/**
  * The cheap first gate of Call #1's pipeline — a regex pre-filter that runs
  * BEFORE the LLM (docs/ARCHITECTURE.md §6). It is **reject-on-match, never
  * strip-and-continue**: a match means the turn is refused outright with 0 LLM
  * calls; the message is never "cleaned" and forwarded, because a
  * partially-stripped injection is still effective.
  *
  * The denylist is deliberately narrow and phrase-specific ("ignore all
  * previous instructions", "reveal the system prompt", "you are now") — NOT
  * bare words like "ignore" or "system", which appear in ordinary shopping
  * talk ("ignore the mesh ones, I need waterproof leather") and would produce
  * false positives that reject legitimate requests before they ever reach
  * Call #1. Anything the regex does not catch still has to pass the LLM's
  * fail-closed validation, so a narrow pre-filter is safe by construction.
  */
object RegexPreFilter {

  private val Denylist: List[String] = List(
    // --- Discarding / overriding prior instructions ---
    """\b(?:ignore|disregard|forget|repeat|say|copy|paste|restate)\b\s+(?:all\s+|any\s+|your\s+)?(?:previous|prior|earlier)\s+(?:instructions?|prompts?|messages?|rules)""",
    // --- Revealing the system prompt / internal instructions ---
    """\b(?:reveal|show|display|print|output|share|repeat|tell)\b\s+(?:me\s+|to\s+me\s+)?(?:the\s+|your\s+|all\s+)?(?:system\s+)?(?:prompts?|instructions?|rules?)""",
    """\bsystem\s+prompts?\b""",
    // --- Persona / role adoption ---
    """\byou\s+are\s+now\b""",
    """\bpretend\s+(?:that\s+)?you\s+are\b""",
    """\b(?:act|behave)\s+as\b\s+(?:an?\s+|the\s+)?(?:ai|chatbot|assistant|language\s+model|persona|pirate|robot|hacker|developer)""",
    // --- Jailbreak modes ---
    """\bdeveloper\s+mode\b""",
    """\bdan\s+mode\b"""
  )

  private val Compiled: List[Pattern] =
    Denylist.map(pattern => Pattern.compile(pattern, Pattern.CASE_INSENSITIVE))

  /** True if the message matches any denylist phrase, meaning the turn is
    * rejected outright (0 LLM calls). The message is never altered — the
    * result is a plain block/no-block decision.
    */
  def isBlocked(message: String): Boolean =
    Compiled.exists(_.matcher(message).find())
}
