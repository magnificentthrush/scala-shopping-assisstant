# Backend auth files

Short reference for the Scala files added for the auth pipeline (register → verify email → login). Full design: [`docs/authPlan.md`](../docs/authPlan.md).

Not covered here (existed before auth): LLM clients, `PromptValidator`, `ValidationResult`.

---

## How they fit together

```
Main
  → Cors (every response)
  → HealthRoutes (/ , /health)
  → AuthRoutes
        → @rateLimited on register / login
        → AuthService          business logic
            → PasswordHasher   hash / check passwords
            → UserRepo         read/write users in DB
                → SupabaseRestClient   HTTP to Supabase
            → EmailService     send (or log) verification email
            → JwtService       issue / check login JWT
            → AppConfig        secrets & settings
  → @authed(jwt)               (ready for future protected routes)
```

Domain types (`User.scala`, `NullableOption.scala`) are the data shapes those layers pass around.

---

## `src/main/scala/assistant/Main.scala`

**What it is:** App entrypoint. Loads config, builds auth services, mounts routes, starts Cask.

| Name | What it does |
| --- | --- |
| `mainDecorators` | Applies `Cors` to every matched response |
| `allRoutes` | `HealthRoutes` + `AuthRoutes` |
| `main` | Prints ready URLs, then starts the server |

---

## `src/main/scala/assistant/http/Cors.scala`

**What it is:** Decorator that adds CORS headers so the frontend (`FRONTEND_URL`) can call the API from the browser.

| Name | What it does |
| --- | --- |
| `Cors(allowedOrigin)` | Adds `Access-Control-Allow-Origin`, `-Headers` (`Authorization`, `Content-Type`), `-Methods`, `-Max-Age` |

---

## `src/main/scala/assistant/http/HealthRoutes.scala`

**What it is:** Simple health checks, separate from auth routes.

| Name | What it does |
| --- | --- |
| `GET /` | `{ "message": "ShopPilot backend is running" }` |
| `GET /health` | `{ "status": "ok" }` |

---

## `src/main/scala/assistant/http/AuthRoutes.scala`

**What it is:** The three auth HTTP endpoints. Thin: parse → `AuthService` → status + JSON.

| Name | What it does |
| --- | --- |
| `POST /api/auth/register` | Rate-limited. Creates account; `201` with user + `needsVerification` (+ `verificationToken` only when email is off) |
| `GET /api/auth/verify-email?token=` | Marks email verified; `200 { verified: true }` |
| `POST /api/auth/login` | Rate-limited. Returns user + JWT when credentials OK and email verified |
| `OPTIONS` on those paths | Empty `204` so browser CORS preflight works |
| On `AuthFailure` | Returns that status + `{ error, code? }` |

---

## `src/main/scala/assistant/config/AppConfig.scala`

**What it is:** One object that holds all auth-related settings (secrets, URLs, email config). Loaded once at startup from environment variables — does not read the `.env` file itself (Docker Compose / your shell puts those vars into the process env).

| Name | What it does |
| --- | --- |
| `AppConfig` (case class fields) | Holds `jwtSecret`, `jwtExpiresInHours`, `supabaseUrl`, `supabaseKey`, `resendApiKey`, `emailFrom`, `frontendUrl` |
| `emailEnabled` | `true` if `RESEND_API_KEY` is set (send real email); `false` otherwise (Phase 1 fallback) |
| `fromEnv()` | Reads env vars and builds an `AppConfig`. Crashes if `JWT_SECRET`, `SUPABASE_URL`, or `SUPABASE_KEY` are missing |

---

## `src/main/scala/assistant/domain/User.scala`

**What it is:** Data shapes for users and auth request/response JSON (with upickle so they convert to/from JSON).

| Name | What it is |
| --- | --- |
| `User` | Full DB row for a user (includes password hash and verification fields — **never** send this whole thing to the frontend) |
| `RegisterRequest` | Body for signup: full name, email, password |
| `LoginRequest` | Body for login: email, password |
| `AuthUserResponse` | Safe user info for API responses: id, fullName, email only |
| `AuthUserResponse.fromUser` | Copies id / name / email out of a `User` |
| `ErrorBody` | Standard error JSON: message + optional machine code (e.g. `EMAIL_TAKEN`) |

---

## `src/main/scala/assistant/domain/NullableOption.scala`

**What it is:** Fix for how upickle 3.x handles optional fields. Without it, JSON `null` from Supabase can become a broken Scala `null` instead of `None`.

| Name | What it does |
| --- | --- |
| `nullableOptionRW` | Teaches upickle: JSON `null` ↔ `None`, and `Some(value)` ↔ bare JSON value (not a weird array). Import this next to any case class that has `Option[...]` fields talking to Supabase |

---

## `src/main/scala/assistant/auth/PasswordHasher.scala`

**What it is:** Hashes and checks passwords with Argon2id. Salt is handled inside the library.

| Name | What it does |
| --- | --- |
| `hash(password)` | Turns a plaintext password into a safe hash string to store in the DB |
| `verify(password, hash)` | Returns `true` if the password matches the stored hash, else `false` |

---

## `src/main/scala/assistant/auth/JwtService.scala`

**What it is:** Creates and checks login JWTs (signed with `JWT_SECRET`). Tokens are **not** stored in the DB — the signature proves they are valid.

| Name | What it does |
| --- | --- |
| `issue(userId, email)` | Builds a signed JWT containing user id (`sub`), email, issued-at, and expiry |
| `verify(token)` | Checks signature + expiry; returns `Some(JwtPayload)` if OK, `None` if bad/expired/fake |
| `JwtPayload` | Simple result of a successful verify: `userId` + `email` |

---

## `src/main/scala/assistant/repo/SupabaseRestClient.scala`

**What it is:** Low-level HTTP helper for Supabase’s REST API (PostgREST). Other code should not call Supabase URLs directly — go through this (or a repo that uses it).

| Name | What it does |
| --- | --- |
| `get(table, params)` | Fetches rows (e.g. filter by email). Returns JSON text |
| `post(table, jsonBody)` | Inserts row(s); asks Supabase to return the inserted row(s) |
| `patch(table, params, jsonBody)` | Updates matching row(s); returns the updated row(s) |

---

## `src/main/scala/assistant/repo/UserRepo.scala`

**What it is:** All database operations for the `users` table. No password hashing or JWT logic here — just read/write rows.

| Name | What it does |
| --- | --- |
| `findByEmail(email)` | Finds a user by email (lowercased so case doesn’t matter). `None` if not found |
| `findByVerificationTokenHash(tokenHash)` | Finds a user by the hashed email-verification token |
| `insert(...)` | Creates a new user (password hash + verification token fields). DB sets `email_verified = false` by default |
| `markVerified(userId)` | Sets `email_verified = true` and clears verification token columns (link becomes single-use) |

---

## `src/main/scala/assistant/services/EmailService.scala`

**What it is:** How verification emails are sent. `AuthService` only calls the trait; which implementation runs is chosen at startup.

| Name | What it does |
| --- | --- |
| `EmailService` (trait) | Contract: `sendVerificationEmail(to, link)` |
| `EmailService.fromConfig` | Picks Resend if API key is set, otherwise NoOp |
| `NoOpEmailService` | Does **not** send email — only logs the link (Phase 1 / no Resend) |
| `ResendEmailService` | Sends a real email via Resend’s HTTP API from `EMAIL_FROM` (e.g. `noreply@scalainterns.dev`) |

---

## `src/main/scala/assistant/services/AuthService.scala`

**What it is:** The brain of auth. Wires hasher + repo + email + JWT. HTTP routes call these methods and turn the result into status codes + JSON — not redo this logic.

| Name | What it does |
| --- | --- |
| `register(req)` | Validates input, rejects taken emails, hashes password, saves user, sends/logs verification email. On success returns user + `needsVerification` (+ raw `verificationToken` only when email is disabled) |
| `verifyEmail(rawToken)` | Hashes the token, finds the user, checks expiry, marks email verified. Fails with invalid/expired codes if the link is bad |
| `login(req)` | Checks email/password, requires verified email, returns user + JWT |
| `AuthFailure` | Error result: HTTP status, message, optional code (for routes to return) |
| `RegisterResult` | Success shape for register |
| `VerifyResult` | Success shape for verify (`verified: true`) |
| `LoginResult` | Success shape for login (user + token) |

---

## `src/main/scala/assistant/auth/AuthedRoute.scala`

**What it is:** Cask decorator `@authed(jwt)` for protected routes. Used later by conversation/session endpoints (not by register/login themselves).

| Name | What it does |
| --- | --- |
| `authed(jwt)` | Annotation class. Checks `Authorization: Bearer <token>`, verifies with `JwtService`, injects `userId` into the handler |
| On failure | Returns `401` with `ErrorBody` code `UNAUTHORIZED` (missing/bad/expired token). Never logs the token |

---

## `src/main/scala/assistant/auth/RateLimiter.scala`

**What it is:** Cask decorator `@rateLimited()` for abuse protection on auth endpoints (register/login). In-memory on purpose for a single Cask process — solid and simple, not a temporary stub.

| Name | What it does |
| --- | --- |
| `rateLimited(maxRequests, windowMs)` | Annotation class. Default **10 requests / 60 seconds** per IP. Each `@rateLimited()` site has its own counters |
| `rateLimited.clientIp` | Undertow peer address only (never trusts client `X-Forwarded-For`) |
| `SlidingWindowCounter` | Counter: `tryAcquire(key)` under limit records the hit; over limit denies. Prunes expired/idle keys |
| On over limit | Returns `429` with `ErrorBody` code `RATE_LIMITED` |

---

## Next (auth plan step 14+)

Manual `curl` end-to-end, then frontend wiring (steps 15–20).
