package assistant

import assistant.config.AppConfig
import assistant.db.Database
import assistant.http._
import assistant.repo._
import assistant.services._
import assistant.services.llm.GemmaClient
import assistant.services.providers.SupabaseProductProvider

object Main extends cask.Main {
  override def host: String = "0.0.0.0"
  override def port: Int = 8080

  val config = AppConfig.load()
  val db = new Database(config.databaseUrl)

  val userRepo = new UserRepo(db)
  val conversationRepo = new ConversationRepo(db)
  val chatSessionRepo = new ChatSessionRepo(db)
  val conversationStateRepo = new ConversationStateRepo(db)
  val messageRepo = new MessageRepo(db)
  val productRepo = new ProductRepo(db)

  val authService = new AuthService(userRepo, config.jwtSecret)
  val conversationService = new ConversationService(db, conversationRepo, chatSessionRepo, conversationStateRepo, messageRepo)
  val llmClient = new GemmaClient(config.gemmaApiKey)
  val productProvider = new SupabaseProductProvider(productRepo)
  val chatService = new ChatService(
    db,
    chatSessionRepo,
    conversationRepo,
    conversationStateRepo,
    messageRepo,
    llmClient,
    productProvider,
    config.llmLogging
  )
  val productService = new ProductService(productProvider)

  val authRoutes = new AuthRoutes(authService, userRepo, config.jwtSecret)
  val conversationRoutes = new ConversationRoutes(conversationService, config.jwtSecret)
  val chatRoutes = new ChatRoutes(chatService, config.jwtSecret)
  val productRoutes = new ProductRoutes(productService)
  val healthRoutes = new HealthRoutes()
  val corsRoutes = new CorsRoutes()

  override def allRoutes = Seq(
    authRoutes,
    conversationRoutes,
    chatRoutes,
    productRoutes,
    healthRoutes,
    corsRoutes
  )

  val publicUrl = sys.env.getOrElse("BACKEND_URL", s"http://localhost:$port")
  println(s"")
  println(s"  ShopPilot backend ready")
  println(s"  ➜  Local:   $publicUrl")
  println(s"  ➜  Health:  $publicUrl/health")
  println(s"")
}