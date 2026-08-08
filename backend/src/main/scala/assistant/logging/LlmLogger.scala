package assistant.logging

import java.io.{FileWriter, PrintWriter}
import java.nio.file.{Files, Paths}
import java.time.Instant

/** Writes `backend/logs/llm.jsonl` — one JSON object per LLM API call
  * (not per user turn: a validated turn makes two calls, and ARCHITECTURE.md
  * §7 is explicit that conflating them corrupts latency/token accounting).
  *
  * Controlled by `LLM_LOGGING`. In production this should be `false`: the
  * doc says production keeps only application errors, auth events, and
  * security logs — not full LLM request/response bodies.
  */
object LlmLogger {
  private val logsDir = "logs"
  Files.createDirectories(Paths.get(logsDir))

  def logValidationCall(
      enabled: Boolean,
      sessionId: String,
      userId: String,
      userMessage: String,
      conversationState: ujson.Value,
      safe: Boolean,
      reason: String,
      latencyMs: Long,
      inputTokens: Int,
      outputTokens: Int
  ): Unit = {
    if (!enabled) return
    val entry = ujson.Obj(
      "timestamp" -> Instant.now().toString,
      "sessionId" -> sessionId,
      "userId" -> userId,
      "callType" -> "validation",
      "userMessage" -> userMessage,
      "conversationState" -> conversationState,
      "safe" -> safe,
      "reason" -> reason,
      "latencyMs" -> latencyMs,
      "inputTokens" -> inputTokens,
      "outputTokens" -> outputTokens
    )
    append(entry)
  }

  def logAssistantCall(
      enabled: Boolean,
      sessionId: String,
      userId: String,
      conversationState: ujson.Value,
      filters: ujson.Value,
      assistantResponse: String,
      latencyMs: Long,
      inputTokens: Int,
      outputTokens: Int
  ): Unit = {
    if (!enabled) return
    val entry = ujson.Obj(
      "timestamp" -> Instant.now().toString,
      "sessionId" -> sessionId,
      "userId" -> userId,
      "callType" -> "assistant",
      "conversationState" -> conversationState,
      "filters" -> filters,
      "assistantResponse" -> assistantResponse,
      "latencyMs" -> latencyMs,
      "inputTokens" -> inputTokens,
      "outputTokens" -> outputTokens
    )
    append(entry)
  }

  private def append(entry: ujson.Obj): Unit = {
    val writer = new PrintWriter(new FileWriter(s"$logsDir/llm.jsonl", true))
    try writer.println(ujson.write(entry))
    finally writer.close()
  }
}
