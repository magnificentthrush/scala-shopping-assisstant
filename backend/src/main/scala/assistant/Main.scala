package assistant

import java.util.concurrent.CountDownLatch

import assistant.auth.JwtService
import assistant.config.AppConfig
import assistant.http.{AuthRoutes, ConversationRoutes, Cors, HealthRoutes, MessageRoutes}
import assistant.repo.{
  ChatSessionRepo,
  ConversationRepo,
  ConversationStateRepo,
  MessageRepo,
  SupabaseProductProvider,
  SupabaseRestClient,
  UserRepo
}
import assistant.services.{
  AssistantService,
  AuthService,
  ConversationService,
  EmailService,
  GeminiLLMClient,
  MessageValidationService
}

/** Backend entrypoint. Wires config → repos/services → HTTP routes and
  * applies app-wide CORS (docs/authPlan.md §7 step 13).
  */
object Main extends cask.Main {
  override def host: String = "0.0.0.0"
  override def port: Int =
    sys.env.get("PORT").flatMap(p => scala.util.Try(p.toInt).toOption).getOrElse(8080)

  private val config = AppConfig.fromEnv()
  // One PostgREST client shared across all repos — avoids redundant HTTP
  // client instances for the same base URL and credentials.
  private val rest = new SupabaseRestClient(config)
  private val jwt = new JwtService(config)
  private val authService = new AuthService(
    config = config,
    users = new UserRepo(rest),
    emails = EmailService.fromConfig(config),
    jwt = jwt
  )
  // One LLM client for the whole app — used by Call #1 validation today and
  // Call #2 (assistant) later (docs/call1Plan.md §2, §7 step 8).
  private val llmClient = new GeminiLLMClient(config.gemmaApiKey)
  private val messageValidationService = new MessageValidationService(llmClient)
  private val conversationStateRepo = new ConversationStateRepo(rest)
  private val messageRepo = new MessageRepo(rest)
  private val conversationService = new ConversationService(
    chatSessions = new ChatSessionRepo(rest),
    conversations = new ConversationRepo(rest),
    conversationStates = conversationStateRepo,
    messages = messageRepo
  )
  private val productProvider = new SupabaseProductProvider(rest)
  private val assistantService = new AssistantService(
    llmClient,
    productProvider,
    conversationStateRepo,
    messageRepo,
    new ConversationRepo(rest)
  )

  override def mainDecorators: Seq[cask.RawDecorator] =
    Seq(new Cors(config.frontendUrl))

  override def allRoutes: Seq[cask.Routes] =
    Seq(
      HealthRoutes(),
      AuthRoutes(authService),
      MessageRoutes(jwt, messageValidationService, conversationService, assistantService),
      ConversationRoutes(jwt, conversationService)
    )

  override def main(args: Array[String]): Unit = {
    val publicUrl = sys.env.getOrElse("BACKEND_URL", s"http://localhost:$port")
    println("")
    println("  ShopPilot backend ready")
    println(s"  ➜  Local:   $publicUrl")
    println(s"  ➜  Health:  $publicUrl/health")
    println(s"  ➜  Auth:    $publicUrl/api/auth/register|login|verify-email")
    println(s"  ➜  Messages: $publicUrl/api/sessions/:sessionId/messages")
    println(s"  ➜  Conversations: $publicUrl/api/conversations (start|list|resume|rename|delete)")
    println("")
    super.main(args)
    // Undertow starts non-blocking; without this the JVM exits right away
    // under `sbt runMain` (Docker's `~runMain` only restarts on file changes).
    new CountDownLatch(1).await()
  }
}
