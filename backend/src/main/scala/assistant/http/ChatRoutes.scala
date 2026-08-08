package assistant.http

import assistant.auth.Auth
import assistant.http.Dto._
import assistant.services.ChatService
import upickle.default._

/** Route -> Service -> Repository/LLM/ProductProvider -> Supabase+Gemma,
  * for the single most important endpoint in the app. All the actual
  * pipeline logic (regex filter, two Gemma calls, retrieval, persistence)
  * lives in `ChatService.sendMessage` — this route only authenticates,
  * parses the body, and maps the result to JSON.
  */
class ChatRoutes(chatService: ChatService, jwtSecret: String) extends cask.Routes with RouteSupport {

  /** POST /api/sessions/:sessionId/messages
    * Request:  { message: string }
    * Response: 200 { sessionId, conversationId, mode, reply,
    *                  followUpQuestion, products, userMessage, assistantMessage }
    * Errors: 404 SESSION_NOT_FOUND, 403 not yours, 422 REJECTED,
    *         500 ASSISTANT_FAILED
    */
  @cask.post("/api/sessions/:sessionId/messages")
  def sendMessage(sessionId: String, request: cask.Request): cask.Response[ujson.Value] = handle {
    val userId = Auth.requireUser(request, jwtSecret)
    val req = read[SendMessageRequest](request.text())
    respond(chatService.sendMessage(sessionId, userId, req.message)) { turn =>
      ok(
        writeJs(
          ChatMessageResponse(
            sessionId = turn.sessionId,
            conversationId = turn.conversationId,
            mode = turn.mode,
            reply = turn.reply,
            followUpQuestion = turn.followUpQuestion,
            products = turn.products.map(ProductDto.from),
            userMessage = MessageDto.from(turn.userMessage),
            assistantMessage = MessageDto.from(turn.assistantMessage, turn.products)
          )
        )
      )
    }
  }

  initialize()
}
