package assistant

import java.util.concurrent.CountDownLatch

import assistant.auth.JwtService
import assistant.config.AppConfig
import assistant.http.{AuthRoutes, Cors, HealthRoutes, MessageRoutes}
import assistant.repo.{
  ChatSessionRepo,
  ConversationRepo,
  ConversationStateRepo,
  MessageRepo,
  SupabaseRestClient,
  UserRepo
}
import assistant.services.{
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
  override def port: Int = 8080

  private val config = AppConfig.fromEnv()
  private val jwt = new JwtService(config)
  private val authService = new AuthService(
    config = config,
    users = new UserRepo(new SupabaseRestClient(config)),
    emails = EmailService.fromConfig(config),
    jwt = jwt
  )
  // One LLM client for the whole app — used by Call #1 validation today and
  // Call #2 (assistant) later (docs/call1Plan.md §2, §7 step 8).
  private val llmClient = new GeminiLLMClient(config.gemmaApiKey)
  private val messageValidationService = new MessageValidationService(llmClient)

  // Six endpoints' repos share one PostgREST client, same as `authService`.
  private val rest = new SupabaseRestClient(config)
  private val conversationService = new ConversationService(
    chatSessions = new ChatSessionRepo(rest),
    conversations = new ConversationRepo(rest),
    conversationStates = new ConversationStateRepo(rest),
    messages = new MessageRepo(rest)
  )

  override def mainDecorators: Seq[cask.RawDecorator] =
    Seq(new Cors(config.frontendUrl))

  override def allRoutes: Seq[cask.Routes] =
    Seq(
      HealthRoutes(),
      AuthRoutes(authService),
      MessageRoutes(jwt, messageValidationService, conversationService)
    )

  override def main(args: Array[String]): Unit = {
    val publicUrl = sys.env.getOrElse("BACKEND_URL", s"http://localhost:$port")
    println("")
    println("  ShopPilot backend ready")
    println(s"  ➜  Local:   $publicUrl")
    println(s"  ➜  Health:  $publicUrl/health")
    println(s"  ➜  Auth:    $publicUrl/api/auth/register|login|verify-email")
    println(s"  ➜  Messages: $publicUrl/api/sessions/:sessionId/messages")
    println("")
    super.main(args)
    // Undertow starts non-blocking; without this the JVM exits right away
    // under `sbt runMain` (Docker's `~runMain` only restarts on file changes).
    new CountDownLatch(1).await()
  }
}
