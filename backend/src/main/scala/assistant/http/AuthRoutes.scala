package assistant.http

import assistant.auth.Auth
import assistant.http.Dto._
import assistant.repo.UserRepo
import assistant.services.AuthService
import assistant.logging.Logger
import upickle.default._

/** Route -> Service -> Repository -> Supabase, for everything under
  * `/api/auth`, plus the small account routes (`/me`, `/logout`,
  * `/api/profile`) the mentor brief also asked for.
  *
  * This class holds no SQL and no bcrypt/JWT calls itself — it only:
  *   1. parses the incoming JSON into a typed request DTO,
  *   2. calls the one `AuthService` method that does the real work,
  *   3. converts the `Either[AppError, A]` result into an HTTP response.
  */
class AuthRoutes(authService: AuthService, userRepo: UserRepo, jwtSecret: String) extends cask.Routes with RouteSupport {

  /** POST /api/auth/register — no auth required.
    * Request:  { fullName, email, password }
    * Response: 201 { user: {id, fullName, email}, token }
    * Errors:   400 bad fields, 409 EMAIL_TAKEN
    */
  @cask.post("/api/auth/register")
  def register(request: cask.Request): cask.Response[ujson.Value] = handle {
    val req = read[RegisterRequest](request.text())
    respond(authService.register(req.fullName, req.email, req.password)) { result =>
      ok(writeJs(AuthResponse(UserDto.from(result.user), result.token)), status = 201)
    }
  }

  /** POST /api/auth/login — no auth required.
    * Request:  { email, password }
    * Response: 200 { user, token }
    * Errors:   401 INVALID_CREDENTIALS
    */
  @cask.post("/api/auth/login")
  def login(request: cask.Request): cask.Response[ujson.Value] = handle {
    val req = read[LoginRequest](request.text())
    respond(authService.login(req.email, req.password)) { result =>
      ok(writeJs(AuthResponse(UserDto.from(result.user), result.token)))
    }
  }

  /** GET /me — auth required. Returns the caller's own profile, resolved
    * from the JWT's `sub` claim, never from a client-supplied id (that
    * would be an IDOR).
    */
  @cask.get("/me")
  def me(request: cask.Request): cask.Response[ujson.Value] = handle {
    val userId = Auth.requireUser(request, jwtSecret)
    respond(authService.me(userId))(user => ok(writeJs(UserDto.from(user))))
  }

  /** POST /logout — auth required. Auth is stateless JWT (no server-side
    * session table), so there is nothing to invalidate server-side; this
    * endpoint exists so the frontend has a single consistent place to hit
    * before discarding its stored token, and so the action is auditable.
    */
  @cask.post("/logout")
  def logout(request: cask.Request): cask.Response[ujson.Value] = handle {
    val userId = Auth.requireUser(request, jwtSecret)
    Logger.security(s"User logged out: userId=$userId")
    ok(ujson.Obj("status" -> "ok"))
  }

  /** PATCH /api/profile — auth required. Only `fullName` is editable; see
    * the comment on `UserRepo.updateFullName` for why email/password are
    * out of scope here.
    */
  @cask.patch("/api/profile")
  def updateProfile(request: cask.Request): cask.Response[ujson.Value] = handle {
    val userId = Auth.requireUser(request, jwtSecret)
    val body = ujson.read(request.text())
    val fullName = body.obj.get("fullName").map(_.str).getOrElse("")
    if (fullName.trim.isEmpty) {
      ErrorMapping.toResponse(assistant.domain.AppError.BadRequest("fullName is required"))
    } else {
      userRepo.updateFullName(userId, fullName.trim) match {
        case Some(user) => ok(writeJs(UserDto.from(user)))
        case None       => ErrorMapping.toResponse(assistant.domain.AppError.NotFound("User not found"))
      }
    }
  }

  initialize()
}
