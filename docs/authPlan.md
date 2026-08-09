# Auth Pipeline Plan — ShopPilot

This is the implementation plan for real authentication: registration with email verification, login, JWT issuance/verification, and password hashing. It supersedes the mocked `USE_MOCK_API = true` behavior in `frontend/src/api/auth.ts` and fills in the empty `backend/src/main/scala/assistant/auth/` package.

Build this **one task at a time, in the order listed in [§7](#7-sequenced-task-list-build-in-this-order)** — each step depends on the ones before it (DB before repo, repo before service, service before routes, backend before the frontend can stop mocking).

**Quality bar:** Auth must be **solid but simple** — correct security behavior, clear seams, no half-measures justified as "MVP" or temporary workarounds. Prefer a small correct design (e.g. in-memory rate limit for one process, Argon2id, hashed verify tokens) over a fake shortcut. Defer features explicitly (§1 / §8); do not ship soft stubs for what's in scope.

---



## 1. Scope

**In scope for this pass:**

- Register → email verification → login, with the JWT issued **only after** the email is verified.
- Password hashing (Argon2id) — plaintext or reversibly-encrypted passwords are never acceptable.
- JWT issuance and a reusable verification/authorization decorator for protected routes.
- Rate limiting on `register` / `login` (required by `docs/ARCHITECTURE.md` §3).
- All frontend changes needed to stop mocking auth and talk to the real backend.

**Explicitly out of scope for this pass** (confirmed decisions — revisit later if needed):

- Password reset / "forgot password". No reset-token columns, no reset endpoints yet.
- Refresh tokens — already excluded by `docs/ARCHITECTURE.md` §3 ("stateless JWT... if refresh tokens are added later, that gets its own migration and its own docs section").
- A dedicated "resend verification email" endpoint — deferred to [§8 Follow-ups](#8-follow-ups-implement-after-7-is-done); not required for the first working version.

**Contract change vs. the current** `docs/API_CONTRACT.md`**:** that doc currently has `register` return a JWT immediately, with no verification step. This plan changes that: `register` returns `201 { user, needsVerification: true }` with **no token**, and a new `GET /api/auth/verify-email` endpoint is added. `login` gains a `403 EMAIL_NOT_VERIFIED` case. Update `docs/API_CONTRACT.md` as task 2 below so no one builds against the stale shape.

### Phased email delivery

**Email delivery uses Resend** over HTTP, sending from the verified domain `scalainterns.dev` (e.g. `noreply@scalainterns.dev`). Sending sits behind an `EmailService` trait so the rest of auth does not care about the provider:


| Phase                              | Email implementation                                                       | How verification actually happens                                                                                                                                                                           |
| ---------------------------------- | -------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Phase 1 (fallback)**             | `NoOpEmailService` — logs the verification link server-side, sends nothing | Used when `RESEND_API_KEY` is missing. `register`'s response includes the raw `verificationToken` (dev-only). The frontend stores it in `localStorage` and calls `verify-email` itself — no inbox involved. |
| **Phase 2 (target for this pass)** | `ResendEmailService` — `sttp` POST to Resend's API                         | `register` sends a real verification email from `@scalainterns.dev` and **stops** including `verificationToken` in the JSON. The user clicks the link in their inbox.                                       |


`AppConfig.emailEnabled` (`true` iff `RESEND_API_KEY` is non-blank) is the single switch `AuthService` checks — see §6. `VerifyEmail.tsx` reads the token from the URL query param first (email-link path) and falls back to `localStorage` (Phase 1), so both paths share the same verify UI.

**Domain / sender:** From address is `EMAIL_FROM` (default `noreply@scalainterns.dev`). The domain must stay verified in Resend (SPF/DKIM as Resend instructs) so messages land in the inbox, not spam.

---



## 2. Key technical decisions


| Decision                   | Choice                                                                                                                                                 | Why                                                                                                                                                                                                                                                                                                                                                                                           |
| -------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Password hashing           | **Argon2id** via `argon2-jvm`                                                                                                                          | OWASP's current recommendation over bcrypt; handles salting internally; verify path is constant-time by construction                                                                                                                                                                                                                                                                          |
| JWT library                | **jwt-scala**, `jwt-core` module, signed **HS256**                                                                                                     | `jwt-core` has no JSON dependency of its own (`Jwt.encode`/`decodeRaw` take/return the claim as a plain JSON string), so `JwtService` builds/parses that string with the project's existing `upickle`. The `jwt-upickle` module was tried first but pulls in `upickle` 4.4.0, which conflicts with `cask` 0.9.2's `upickle` 3.x — `jwt-core` avoids a second JSON codec without that conflict |
| JWT secret                 | `JWT_SECRET` env var (already present in `.env.example`)                                                                                               | —                                                                                                                                                                                                                                                                                                                                                                                             |
| JWT lifetime               | 7 days, via a configurable `JWT_EXPIRES_IN_HOURS` (default `168`)                                                                                      | Refresh tokens are out of scope; a very short-lived access token with no renewal path would force constant re-logins. 7 days is a deliberate trade-off for this product, not a temporary stand-in                                                                                                                                                                                           |
| Email delivery             | `EmailService` trait; `ResendEmailService` via **sttp** → Resend HTTP API from `scalainterns.dev`; `NoOpEmailService` when `RESEND_API_KEY` is missing | Verified custom domain + Resend matches the deliverability note in `ARCHITECTURE.md` §3; trait keeps the provider out of `AuthService` (see "Phased email delivery" above)                                                                                                                                                                                                                    |
| DB access for auth         | Supabase **REST (PostgREST)**, via `sttp`, using the service-role `SUPABASE_KEY`                                                                       | Matches the established project pattern: "Supabase client for app queries, direct Postgres connection only for migrations" (`docs/project-plan.md` §5)                                                                                                                                                                                                                                        |
| Verification token storage | Store the **SHA-256 hash** of the token; email the raw token in the link                                                                               | A DB leak shouldn't hand out working verification links                                                                                                                                                                                                                                                                                                                                       |
| Rate limiting              | In-memory sliding-window counter per IP, applied as a Cask decorator                                                                                   | Solid and simple for a single Cask process — no Redis required. IP comes from Undertow's peer address (not spoofable `X-Forwarded-For`). If we run multiple replicas later, swap the counter for a shared store; keep the decorator contract. Satisfies `docs/ARCHITECTURE.md` §3                                                                                                                                                                              |
| Password policy            | ≥ 8 characters, ≥ 1 digit, ≥ 1 uppercase letter — enforced on **both** frontend and backend                                                            | The Signup UI already shows this checklist; the actual validator only checked 6 characters. Backend must enforce it independently of the frontend — never trust client-side validation alone                                                                                                                                                                                                  |


---



## 3. Database change

New migration: `data/migrations/007_add_email_verification_to_users.sql`

```sql
-- Adds email verification support to users. Structure only, per the
-- forward-only migration policy in docs/ARCHITECTURE.md §2.
ALTER TABLE users
  ADD COLUMN IF NOT EXISTS email_verified BOOLEAN NOT NULL DEFAULT false,
  ADD COLUMN IF NOT EXISTS verification_token_hash TEXT,
  ADD COLUMN IF NOT EXISTS verification_token_expires_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_users_verification_token_hash
  ON users(verification_token_hash);
```

Apply via `data/scripts/apply_migrations.py` against the shared Supabase project, then announce it in the team channel — same workflow as every other migration in `data/migrations/`.

No changes needed to `conversations` or any other table for this pass.

---



## 4. JWT design

- **Claims:** `sub` (user id), `email`, `iat`, `exp`. Nothing else — keep the token small and avoid putting anything sensitive or mutable (like `fullName`) in it, since it can't be invalidated before `exp`.
- **Signing:** HS256 with `JWT_SECRET` from the environment. Never hardcode the secret; never log the token itself (per `docs/ARCHITECTURE.md` §7 — "never log the credential, password, JWT, or `Authorization` header").
- **Verification:** a reusable Cask decorator (`auth/AuthedRoute.scala`, e.g. `@authed`) that:
  1. Reads the `Authorization: Bearer <jwt>` header.
  2. Validates signature and expiry.
  3. On success, injects `userId: String` into the route handler's parameters.
  4. On failure (missing header, bad signature, expired) → `401 { "error": "...", "code": "UNAUTHORIZED" }`.
- This decorator is intentionally generic — it's the same one every future conversation/session route will use for the ownership checks required by `docs/ARCHITECTURE.md` §3. Build it once, in `assistant/auth/`, not copy-pasted per route.

---



## 5. Password hashing design

- `auth/PasswordHasher.scala` exposes exactly two functions:
  - `hash(password: String): String`
  - `verify(password: String, hash: String): Boolean`
- Both are thin wrappers over `argon2-jvm`'s `Argon2Factory` (Argon2id variant, its default). No custom crypto, no manual salt handling — the library owns that.
- Password policy (enforced independently in both places):
  - Frontend: `frontend/src/utils/validation.ts` → `isValidPassword`.
  - Backend: same rule, checked again during `register`, before hashing. `400` on failure with a message matching the frontend's checklist wording.

---



## 6. Endpoints and business logic



### `POST /api/auth/register`

No auth required.

**Request**

```json
{ "fullName": "Ada Lovelace", "email": "ada@example.com", "password": "correct1Horse" }
```

**Logic**

1. Validate `fullName` non-empty, `email` format, `password` against the policy in §5 → `400` on any failure.
2. Look up `email` (case-insensitive) in `users` → `409 { "error": "Email already registered", "code": "EMAIL_TAKEN" }` if found.
3. `PasswordHasher.hash(password)`.
4. Generate a random verification token (e.g. 32 bytes from `SecureRandom`, base64url-encoded); compute its SHA-256 hash; set expiry `now + 24h`.
5. Insert the user row: `email_verified = false`, `verification_token_hash`, `verification_token_expires_at`.
6. Call `EmailService.sendVerificationEmail(email, link)` where `link = {FRONTEND_URL}/verify-email?token=<raw token>`. Whether this actually sends anything depends on which `EmailService` is wired up (§1, "Phased email delivery") — the `AuthService` code doesn't branch on it.
7. Respond `201`. If `AppConfig.emailEnabled == false` (Phase 1 — Resend not configured), include the raw `verificationToken` in the response so the frontend can complete verification without an inbox. When Resend is configured (Phase 2), this field is omitted.

**Response** `201` **— Phase 1 (Resend not configured)**

```json
{
  "user": { "id": "uuid", "fullName": "Ada Lovelace", "email": "ada@example.com" },
  "needsVerification": true,
  "verificationToken": "raw-token-normally-only-seen-in-the-email-link"
}
```

**Response** `201` **— Phase 2 (Resend configured)**

```json
{ "user": { "id": "uuid", "fullName": "Ada Lovelace", "email": "ada@example.com" }, "needsVerification": true }
```

No JWT `token` field in either phase — the frontend must not treat this as a logged-in state. `verificationToken` is a temporary fallback field that disappears when `RESEND_API_KEY` is set; don't design frontend code that assumes it's always present.

---



### `GET /api/auth/verify-email?token=<raw token>`

No auth required (the token itself is the credential).

**Logic**

1. Hash the incoming `token`; look up a user whose `verification_token_hash` matches **and** `verification_token_expires_at` is in the future.
2. No match → `400 { "error": "...", "code": "TOKEN_INVALID" }`. Expired match → `400 { "error": "...", "code": "TOKEN_EXPIRED" }`.
3. On success: set `email_verified = true`, clear `verification_token_hash` and `verification_token_expires_at` (so the link is single-use) → `200`.

**Response** `200`

```json
{ "verified": true }
```

---



### `POST /api/auth/login`

No auth required (this endpoint issues the auth).

**Request**

```json
{ "email": "ada@example.com", "password": "correct1Horse" }
```

**Logic**

1. Look up user by email (case-insensitive) → `401 INVALID_CREDENTIALS` if not found. **Don't distinguish** "no such user" from "wrong password" in the response — same generic message either way.
2. `PasswordHasher.verify(password, storedHash)` → `401 INVALID_CREDENTIALS` on mismatch.
3. If `email_verified == false` → `403 { "error": "Please verify your email before logging in.", "code": "EMAIL_NOT_VERIFIED" }`.
4. Issue JWT (§4) → `200`.

**Response** `200`

```json
{ "user": { "id": "uuid", "fullName": "Ada Lovelace", "email": "ada@example.com" }, "token": "eyJhbGciOi..." }
```

---



### Sequence diagram — Phase 1 (now, no email service configured)

```mermaid
sequenceDiagram
    participant FE as React
    participant LS as localStorage
    participant BE as Cask Backend
    participant DB as Supabase (REST)

    FE->>BE: POST /api/auth/register
    BE->>BE: validate fields + password policy
    BE->>BE: hash password (Argon2id)
    BE->>DB: INSERT user (email_verified=false, token_hash, expiry)
    BE->>BE: NoOpEmailService logs the link (no real send)
    BE-->>FE: 201 { user, needsVerification: true, verificationToken }
    FE->>LS: store verificationToken

    FE->>LS: read verificationToken
    FE->>BE: GET /api/auth/verify-email?token=...
    BE->>DB: find user by hash(token); check expiry
    BE->>DB: UPDATE email_verified=true, clear token columns
    BE-->>FE: 200 { verified: true }
    FE->>LS: clear verificationToken

    FE->>BE: POST /api/auth/login
    BE->>DB: find user by email
    BE->>BE: Argon2 verify(password, storedHash)
    BE->>BE: check email_verified
    BE->>BE: sign JWT (sub, email, iat, exp)
    BE-->>FE: 200 { user, token }
```





### Sequence diagram — Phase 2 (Resend configured)

Same backend code path, different `EmailService` impl and no `verificationToken` in the response — the frontend reads the token from the emailed link's URL instead of `localStorage`:

```mermaid
sequenceDiagram
    participant FE as React
    participant BE as Cask Backend
    participant DB as Supabase (REST)
    participant Mail as Resend

    FE->>BE: POST /api/auth/register
    BE->>DB: INSERT user (email_verified=false, token_hash, expiry)
    BE->>Mail: send verification email from noreply@scalainterns.dev
    BE-->>FE: 201 { user, needsVerification: true }

    Note over FE: user clicks the link in their inbox
    FE->>BE: GET /api/auth/verify-email?token=... (from URL)
    BE->>DB: find user by hash(token); check expiry
    BE->>DB: UPDATE email_verified=true, clear token columns
    BE-->>FE: 200 { verified: true }

    FE->>BE: POST /api/auth/login
    BE-->>FE: 200 { user, token }
```



---



## 7. Sequenced task list (build in this order)

Each task assumes the ones before it are done — don't skip ahead.

1. **[This document]** `docs/authPlan.md` — done.
2. Update `docs/API_CONTRACT.md` — done (register / verify-email / login; also mirrored in `ARCHITECTURE.md` §3).
3. Write and apply migration `data/migrations/007_add_email_verification_to_users.sql` — done (applied to shared Supabase; `database-schema.md` updated).
4. Add backend dependencies to `backend/build.sbt`: `jwt-scala` (`jwt-core`), `argon2-jvm`, `sttp` (`client3` core) — sttp is also used by `ResendEmailService` for the Resend HTTP API. **Done** — used `jwt-core` instead of `jwt-upickle` (see §2 note on the `upickle` version conflict); `sbt compile` passes clean.
5. `assistant/config/AppConfig.scala` — central env var loader for `JWT_SECRET`, `JWT_EXPIRES_IN_HOURS`, `SUPABASE_URL`, `SUPABASE_KEY`, `RESEND_API_KEY`, `EMAIL_FROM` (default `noreply@scalainterns.dev`), `FRONTEND_URL`, plus `emailEnabled: Boolean` (`RESEND_API_KEY` non-blank). Document vars in `.env.example`. **Done** — vars already documented in `.env.example`; `AppConfig.fromEnv()` reads them with `sys.error` on missing required vars (`JWT_SECRET`, `SUPABASE_URL`, `SUPABASE_KEY`) and safe defaults for the rest.
6. `assistant/domain/` — `User`, `RegisterRequest`, `LoginRequest`, `AuthUserResponse`, `ErrorBody` case classes with upickle `ReadWriter`s. **Done** — all five live in `domain/User.scala`; `User`'s fields use `@key("snake_case")` to match PostgREST's column names directly. Also added `domain/NullableOption.scala`: upickle 3.3.1's default `Option[T]` codec deserializes a JSON `null` into a raw Scala `null` instead of `None` (NPEs on `.isEmpty`/`.map`) and serializes `Some(t)` as a boxed `[t]` array instead of a bare value — verified empirically with a throwaway `runMain` script, then deleted it. `NullableOption.nullableOptionRW` fixes both directions to match upickle 4.x behavior; **any future domain class with an** `Option[_]` **field must import it** (`UserRepo` in step 8 will need this for reading Supabase's nullable columns).
7. `assistant/auth/PasswordHasher.scala` (Argon2id `hash`/`verify`) — sanity-check with a quick hash → verify round trip before moving on. **Done** — `Argon2Factory.create(Argon2Types.ARGON2id)` explicitly (the no-arg `create()` defaults to the weaker Argon2i), with OWASP's 2023 minimum cost params (19 MiB memory, 2 iterations, parallelism 1). Verified via a throwaway `runMain` script (then deleted): produced `$argon2id$v=19$m=19456,t=2,p=1$...`, correct password verifies `true`, wrong password `false`, and two hashes of the same password differ (per-hash salting confirmed). ~150ms per hash.
8. `assistant/repo/SupabaseRestClient.scala` (generic PostgREST GET/POST/PATCH helper) + `assistant/repo/UserRepo.scala` (`findByEmail`, `insert`, `findByVerificationTokenHash`, `markVerified`). **Done** — verified against the real shared Supabase project with a throwaway `runMain` script (insert → findByEmail (mixed-case) → findByVerificationTokenHash → markVerified → confirm cleared/verified fields → delete the test row), then deleted the script. Two things worth flagging:
  - `HttpURLConnectionBackend` **cannot send** `PATCH` — `java.net.HttpURLConnection` only allows a fixed method whitelist that excludes `PATCH`, and sttp's usual reflection-based workaround for that doesn't work under this JDK's module encapsulation (`ProtocolException: Invalid HTTP method: PATCH`, caught by actually running against Supabase, not just compiling). Switched to `HttpClientSyncBackend` (Java 11+'s native `HttpClient`, bundled in `core` for our sttp version), which supports `PATCH` natively.
  - **Email case-insensitivity is done by normalizing to lowercase**, not PostgREST's `ilike` filter — `ilike` treats `_` and `%` as SQL wildcards, so an email containing `_` (a valid character) would wrongly match unrelated addresses. `UserRepo` lowercases on both insert and lookup and always filters with `eq`, so callers never think about casing.
  - `findByVerificationTokenHash` intentionally does not filter out expired tokens at the DB level — it returns the row regardless, so `AuthService` (step 11) can distinguish "no such token" (`TOKEN_INVALID`) from "found but expired" (`TOKEN_EXPIRED`) by checking `verificationTokenExpiresAt` itself.
9. `assistant/services/EmailService.scala` — the trait (`sendVerificationEmail(to, link)`) + `ResendEmailService` (sttp → Resend API, `from` = `EMAIL_FROM` on `scalainterns.dev`) + `NoOpEmailService` (logs the link when Resend is not configured). Wire `ResendEmailService` when `emailEnabled` is true. **Done** — `EmailService.fromConfig(config)` picks the impl from `emailEnabled`. Verified with a throwaway `runMain` script (then deleted): `NoOpEmailService` logs the link; with the team's `RESEND_API_KEY` set, `fromConfig` wired `ResendEmailService` and a live `POST https://api.resend.com/emails` to Resend's test address `delivered@resend.dev` from `noreply@scalainterns.dev` returned 2xx. Uses the same `HttpClientSyncBackend` as `SupabaseRestClient` (step 8).
10. `assistant/auth/JwtService.scala` — `issue(userId, email)`, `verify(token)`. **Done** — HS256 via `jwt-core`; claims are `sub` + `email` + `iat`/`exp` (`expiresIn` = `JWT_EXPIRES_IN_HOURS * 3600`). `verify` returns `Option[JwtPayload(userId, email)]` — `None` on bad signature, expiry, or malformed token (callers map all of those to `401 UNAUTHORIZED`). Verified with a throwaway `runMain` script (then deleted): issue → verify round-trip recovers the payload; wrong secret / expired / garbage all return `None`. Token itself is never logged (ARCHITECTURE.md §7).
11. `assistant/services/AuthService.scala` — orchestrates `register` / `verifyEmail` / `login` using steps 7–10. When `AppConfig.emailEnabled == false`, `register` includes the raw token in its result so the route can put it in the JSON response (§6, Phase 1). Business logic lives here, not in the routes (per `docs/project-plan.md` §9: "keep controllers thin"). **Done** — returns `Either[AuthFailure, …]` (`RegisterResult` / `VerifyResult` / `LoginResult`) so step 13's routes only map status + JSON. Verified against real Supabase with a throwaway `runMain` (Phase 1 forced via blank `resendApiKey`, then deleted): register → EMAIL_TAKEN on duplicate → 400 on weak password → EMAIL_NOT_VERIFIED before verify → verify → TOKEN_INVALID on reuse → login issues JWT → INVALID_CREDENTIALS on wrong password. Also fixed a typo that had landed in `PasswordHasher.hash` (`Argon2Factory.createso`) back to `create`.
12. `assistant/auth/AuthedRoute.scala` (JWT decorator) + `assistant/auth/RateLimiter.scala` (per-IP sliding window decorator for `register`/`login`). **Done** — `@authed(jwt)` reads `Authorization: Bearer …`, verifies via `JwtService`, injects `userId: String` on success or returns `401 UNAUTHORIZED`. `@rateLimited()` is an in-memory sliding window (default 10 req / 60s per IP; each annotation site has its own counter so register/login stay independent); over limit → `429 RATE_LIMITED`. IP is Undertow's peer address only (no client-spoofable `X-Forwarded-For`). `SlidingWindowCounter` prunes expired/idle keys. Verified: `sbt compile` clean; smoke (then deleted) confirmed allow/deny/per-key isolation + decorator construction with `JwtService`.
13. `assistant/http/AuthRoutes.scala` — the three Cask routes; mount from `Main.scala`; add CORS (allow `FRONTEND_URL` origin, `Authorization` + `Content-Type` headers) — this is the **first** real cross-origin call the browser will make to the backend, so without this nothing works from the UI at all. **Done** — `AuthRoutes` maps register / verify-email / login onto `AuthService` (`@rateLimited` on register+login; `OPTIONS` preflight per path). `Cors` is a `mainDecorator` that attaches Allow-Origin/Headers/Methods. `HealthRoutes` holds `/` + `/health`. `Main` is now `cask.Main` wiring `AppConfig` → repo/email/jwt/`AuthService` → routes. Uses `@cask.post` + upickle (not `postJson`) so status codes like `201`/`403` work as `Response.Raw`. `sbt compile` clean.
14. Manual `curl` smoke test end-to-end: register → (with Resend: check inbox for link from `@scalainterns.dev`; without Resend: grab `verificationToken` from JSON) → verify → login → confirm the JWT decodes with the expected claims. **Done** — ran a throwaway Phase 1 instance (port 8081, `RESEND_API_KEY` blanked, `SmokeMain` mirroring `Main`, then deleted) against the shared Supabase and verified over HTTP: register → `201` with `{ user, needsVerification, verificationToken }`; login before verify → `403 EMAIL_NOT_VERIFIED`; verify-email → `200 { verified: true }`; token reuse → `400 TOKEN_INVALID` (single-use); login → `200 { user, token }`; JWT payload decodes to `{ sub: <user id>, email, iat, exp }` with `exp - iat == 168h` and the HS256 signature validates against `JWT_SECRET`. Also exercised: weak-password `400`, duplicate (case-insensitive) `409 EMAIL_TAKEN`, wrong-password/unknown-email `401 INVALID_CREDENTIALS`, CORS preflight `204`, and the rate limiter (`429` after 10 login attempts/60s/IP). Test rows deleted from Supabase afterward.
15. Frontend: tighten `frontend/src/utils/validation.ts`'s `isValidPassword` to the real policy (§5); update the checklist label in `frontend/src/pages/Signup/Signup.tsx` ("At least 8 characters"). **Done** — `isValidPassword` now enforces the §5 policy (≥ 8 chars, ≥ 1 digit, ≥ 1 uppercase) with per-rule messages mirroring the checklist; the checklist label and password placeholder in `Signup.tsx` now read "At least 8 characters". Matches the backend's `AuthService.isValidPassword` rule and the `400` message. `tsc -b` and `vite build` pass; `oxlint` clean for the changed files.
16. Frontend: `frontend/src/api/auth.ts` — set `USE_MOCK_API = false`; `register` keeps its current `{ user, needsVerification: true }` return shape (the mock path already matches!) but must stop calling `saveAuth`; if the response includes `verificationToken`, store it in `localStorage` (e.g. key `pendingVerificationToken`); `login` must surface the new `EMAIL_NOT_VERIFIED` code distinctly from a generic failure. **Done** — removed the mock machinery entirely (no `USE_MOCK_API` toggle; the backend is live and verified, dead mock branches would just drift). `register` POSTs to `/api/auth/register`, returns `{ user, needsVerification }`, does **not** call `saveAuth`, and stores the raw token under `pendingVerificationToken` when the backend returns it (Phase 1). `login` POSTs to `/api/auth/login`, saves `{ user, token }`, and propagates `ApiError.code` untouched so `EMAIL_NOT_VERIFIED` (403) is distinguishable from a generic failure. Exported `getPendingVerificationToken`/`clearPendingVerificationToken` for step 17. `tsc -b` and `vite build` pass; `oxlint` clean for the changed file.
17. Frontend: `frontend/src/pages/VerifyEmail/VerifyEmail.tsx` — currently a hardcoded "always succeeds" placeholder; wire it to read `?token=` via `useSearchParams`, **falling back to** `localStorage`**'s** `pendingVerificationToken` when the URL has none (Phase 1), call the real endpoint, render loading/success/error from the actual response, and clear the stored token on success. **Done** — `VerifyEmail` reads `?token=` via `useSearchParams`, falls back to `getPendingVerificationToken()` (Phase 1), calls `GET /api/auth/verify-email?token=...`, and renders loading/success/error from the real response. `TOKEN_EXPIRED` and `TOKEN_INVALID` get distinct messages; the stored `pendingVerificationToken` is cleared on success. A `hasAttempted`/`attemptedToken` ref pair stops StrictMode's dev double-effect from re-consuming the single-use token while still allowing a different `?token=` to verify. `tsc -b` and `vite build` pass; `oxlint` clean for the changed file.
18. Frontend: `frontend/src/pages/Signup/Signup.tsx` — after signup, show "check your email"; keep a Phase-1-only "Verify now" link when `pendingVerificationToken` was stored (no-Resend fallback). With Resend configured, the real inbox link is the primary path.
19. Frontend: `frontend/src/pages/Login/Login.tsx` — show a distinct "please verify your email" message when `EMAIL_NOT_VERIFIED` comes back, instead of the generic error text.
20. End-to-end manual test through the actual UI: sign up → open verification email from `@scalainterns.dev` (or Phase 1 "Verify now") → log in → land on `/`.



---



## 8. Follow-ups (implement after §7 is done)

Do **not** start these until steps 1–20 above are complete and the core auth flow works end-to-end.

### 8.1 Resend verification email / expired-token recovery

**Problem today:** When a user registers, the row stays in `users` with `email_verified = false`. The verification link expires after 24h (`TOKEN_EXPIRED`). We do **not** delete that row. After expiry the user is stuck:


| Action                    | What happens             |
| ------------------------- | ------------------------ |
| Login                     | `403 EMAIL_NOT_VERIFIED` |
| Old verify link           | `400 TOKEN_EXPIRED`      |
| Register same email again | `409 EMAIL_TAKEN`        |


There is no way in-app to get a new link without manual DB cleanup.

**What to build (next pass):**

1. Backend: e.g. `POST /api/auth/resend-verification` `{ "email": "..." }` — if the user exists and is still unverified, mint a **new** raw token, store its SHA-256 hash + new 24h expiry, send (or Phase-1 return) the new link. Rate-limit this endpoint (same concern as register/login).
2. Do **not** delete the user row on token expiry — refresh the token on the existing account instead.
3. Update `docs/API_CONTRACT.md` + Login/Signup UI copy so “verify your email” / “link expired” can offer “Resend verification email.”
4. Optional later: cleanup job that deletes stale unverified accounts after N days — only if product wants that; not required for unblocking users if resend exists.

---



## 9. Files touched (reference)

**Backend — new files:**

```
backend/src/main/scala/assistant/
├── config/AppConfig.scala
├── domain/User.scala                 (+ request/response case classes)
├── domain/NullableOption.scala        (upickle 3.3.1 Option[T] null-safety fix — see §7 step 6)
├── auth/PasswordHasher.scala
├── auth/JwtService.scala
├── auth/AuthedRoute.scala
├── auth/RateLimiter.scala
├── repo/SupabaseRestClient.scala
├── repo/UserRepo.scala
├── services/EmailService.scala        (trait + ResendEmailService + NoOpEmailService)
├── services/AuthService.scala
└── http/AuthRoutes.scala
```

**Backend — modified:** `build.sbt` (adds jwt-scala, argon2-jvm, sttp), `Main.scala` (mount routes + CORS).

**Frontend — modified:** `src/utils/validation.ts`, `src/pages/Signup/Signup.tsx` (checklist copy + Phase-1 "Verify now" affordance), `src/pages/Login/Login.tsx`, `src/pages/VerifyEmail/VerifyEmail.tsx` (URL param + `localStorage` fallback), `src/api/auth.ts` (store `verificationToken` when present).

**Docs — modified:** `docs/API_CONTRACT.md`. **Docs — new:** this file, `data/migrations/007_add_email_verification_to_users.sql`.

**Env — modified:** `.env.example` documents `RESEND_API_KEY` + `EMAIL_FROM` (default `noreply@scalainterns.dev`).

---



## 10. See also

- `[ARCHITECTURE.md](ARCHITECTURE.md)` §3 — authorization/ownership rules, hardening requirements this plan implements.
- `[API_CONTRACT.md](API_CONTRACT.md)` — frozen request/response shapes (to be updated per task 2 above).
- `[database-schema.md](database-schema.md)` — canonical schema reference (update alongside migration 007).
- `[project-plan.md](project-plan.md)` §5 — technology choices (cask, upickle, Supabase REST access pattern) this plan follows.

