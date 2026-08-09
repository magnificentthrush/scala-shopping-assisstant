# API Contract (Frontend Guide) — ShopPilot

Frozen shapes the React app can build against **before** every backend route exists. If an endpoint below is not implemented yet, mock it in the frontend with these request/response bodies so UI work is not blocked.

**Base URL (local Docker):** `http://localhost:8080`  
**Env:** `VITE_API_URL` (defaults to that URL in Compose)

All authenticated routes expect:

```http
Authorization: Bearer <jwt>
Content-Type: application/json
```

JSON field names are **camelCase** on the wire (what React uses). Backend Scala may use the same names via uPickle.

---

## Shared types

```ts
type User = {
  id: string
  fullName: string
  email: string
}

type Product = {
  id: string
  name: string
  brand: string | null
  category: string
  price: number
  originalPrice: number | null
  rating: string | null
  description: string | null
  imageUrl: string | null
  productUrl: string | null
  productSpecifications: string | null
}

type Filters = {
  category?: string | null
  budget?: number | null
  keywords?: string[]
  attributes?: Record<string, string> // e.g. { waterproof: "true" }
}

type Message = {
  id: string
  role: "user" | "assistant"
  content: string
  sequenceNumber: number
  createdAt: string // ISO-8601
  products?: Product[] // present on some assistant turns that recommended items
}

type ConversationSummary = {
  id: string
  title: string | null
  createdAt: string
  updatedAt: string
  lastMessageAt: string
}

type ErrorBody = {
  error: string
  code?: string // machine-readable when useful, e.g. "UNAUTHORIZED", "REJECTED", "NOT_FOUND"
}
```

---

## Auth

Flow: **register → verify email → login**. JWT is issued only after the email is verified. Password policy: ≥ 8 characters, ≥ 1 digit, ≥ 1 uppercase. Full design: [`authPlan.md`](authPlan.md).

### `POST /api/auth/register`

Create an account. No JWT required. Does **not** log the user in.

**Request**

```json
{
  "fullName": "Ada Lovelace",
  "email": "ada@example.com",
  "password": "correct1Horse"
}
```

**Response `201` (Resend configured)**

```json
{
  "user": {
    "id": "uuid",
    "fullName": "Ada Lovelace",
    "email": "ada@example.com"
  },
  "needsVerification": true
}
```

**Response `201` (Resend not configured — dev fallback)**

When `RESEND_API_KEY` is missing, the API also returns `verificationToken` so the frontend can complete verification without an inbox. Do not assume this field is always present.

```json
{
  "user": {
    "id": "uuid",
    "fullName": "Ada Lovelace",
    "email": "ada@example.com"
  },
  "needsVerification": true,
  "verificationToken": "raw-token-normally-only-seen-in-the-email-link"
}
```

Frontend: show “check your email”; do **not** store a JWT. With Resend, the user clicks the link in mail (`/verify-email?token=...`). Without Resend, store `verificationToken` temporarily and call verify yourself.

**Errors**

| Status | When |
| --- | --- |
| `400` | Missing/invalid fields (including password policy) |
| `409` | Email already registered |

```json
{ "error": "Email already registered", "code": "EMAIL_TAKEN" }
```

---

### `GET /api/auth/verify-email?token=<raw token>`

Activate an account. No JWT required — the token in the query string is the credential.

**Response `200`**

```json
{ "verified": true }
```

**Errors**

| Status | When | `code` |
| --- | --- | --- |
| `400` | Missing/invalid token | `TOKEN_INVALID` |
| `400` | Token expired | `TOKEN_EXPIRED` |

```json
{ "error": "This verification link is invalid or has expired.", "code": "TOKEN_INVALID" }
```

---

### `POST /api/auth/login`

**Request**

```json
{
  "email": "ada@example.com",
  "password": "correct1Horse"
}
```

**Response `200`**

```json
{
  "user": {
    "id": "uuid",
    "fullName": "Ada Lovelace",
    "email": "ada@example.com"
  },
  "token": "eyJhbGciOi..."
}
```

Frontend: store `token` (e.g. `localStorage`) and send it on later calls as `Authorization: Bearer <jwt>`.

**Errors**

| Status | When | `code` |
| --- | --- | --- |
| `401` | Bad email/password | `INVALID_CREDENTIALS` |
| `403` | Account exists but email not verified | `EMAIL_NOT_VERIFIED` |

```json
{ "error": "Invalid email or password", "code": "INVALID_CREDENTIALS" }
```

```json
{ "error": "Please verify your email before logging in.", "code": "EMAIL_NOT_VERIFIED" }
```

---

## Conversations & sessions

### Concepts the UI must keep straight

| UI concept | API field | Meaning |
| --- | --- | --- |
| Chat in the sidebar | `conversationId` | Durable history row |
| Active tab / open chat | `sessionId` | Runtime handle; **send this when posting messages** |
| Resume | new `sessionId`, same `conversationId` | History unchanged; new session minted |

---

### `POST /api/conversations` — start new chat

**Auth:** required

**Request body:** empty object or omit body.

```json
{}
```

**Response `201`**

```json
{
  "conversationId": "uuid-conversation",
  "sessionId": "uuid-session",
  "title": null,
  "messages": []
}
```

Frontend: save `sessionId` + `conversationId` for the open chat; clear the message list.

---

### `GET /api/conversations` — list history

**Auth:** required

**Response `200`**

```json
{
  "conversations": [
    {
      "id": "uuid-conversation",
      "title": "Hiking shoes under $120",
      "createdAt": "2026-07-31T10:00:00Z",
      "updatedAt": "2026-07-31T10:15:00Z",
      "lastMessageAt": "2026-07-31T10:15:00Z"
    }
  ]
}
```

Ordered by `lastMessageAt` descending (newest activity first).

---

### `POST /api/conversations/{conversationId}/resume`

**Notes:** Creates a new chat session for the conversation and returns the full conversation history along with the new `sessionId`.

**Auth:** required (must own the conversation)

**Response `200`**

```json
{
  "conversationId": "uuid-conversation",
  "sessionId": "uuid-new-session",
  "title": "Hiking shoes under $120",
  "messages": [
    {
      "id": "uuid-msg-1",
      "role": "user",
      "content": "I need hiking shoes",
      "sequenceNumber": 1,
      "createdAt": "2026-07-31T10:00:00Z"
    },
    {
      "id": "uuid-msg-2",
      "role": "assistant",
      "content": "What's your budget?",
      "sequenceNumber": 2,
      "createdAt": "2026-07-31T10:00:05Z",
      "products": []
    }
  ]
}
```

Frontend: replace open-chat state with returned `sessionId` + `messages`.

**Errors**

| Status | When |
| --- | --- |
| `401` | Missing/invalid JWT |
| `403` | JWT valid but not the owner |
| `404` | Conversation does not exist |

---

### `PATCH /api/conversations/{conversationId}` — rename

**Auth:** required

**Request**

```json
{
  "title": "Waterproof hiking shoes"
}
```

**Response `200`**

```json
{
  "id": "uuid-conversation",
  "title": "Waterproof hiking shoes",
  "createdAt": "2026-07-31T10:00:00Z",
  "updatedAt": "2026-07-31T10:20:00Z",
  "lastMessageAt": "2026-07-31T10:15:00Z"
}
```

---

### `DELETE /api/conversations/{conversationId}` — hard delete

**Auth:** required

**Response `204`** — empty body.

Frontend: remove the item from the sidebar list; if it was the open chat, start a new chat or show empty state.

---

## Messages (main chat turn)

### `POST /api/sessions/{sessionId}/messages`

**Auth:** required  
**Path:** `sessionId` from new-chat or resume — **not** `conversationId`.

**Request**

```json
{
  "message": "Under $120 and waterproof"
}
```

**Response `200` (normal assistant reply)**

```json
{
  "sessionId": "uuid-session",
  "conversationId": "uuid-conversation",
  "mode": "recommend",
  "reply": "Here are a few waterproof hiking shoes under $120 that match what you asked for.",
  "followUpQuestion": null,
  "products": [
    {
      "id": "abc123",
      "name": "TrailGuard Hiking Shoe",
      "brand": "Acme",
      "category": "hiking shoes",
      "price": 99.99,
      "originalPrice": 129.99,
      "rating": "4.3",
      "description": "...",
      "imageUrl": "https://...",
      "productUrl": "https://...",
      "productSpecifications": "..."
    }
  ],
  "userMessage": {
    "id": "uuid-user-msg",
    "role": "user",
    "content": "Under $120 and waterproof",
    "sequenceNumber": 3,
    "createdAt": "2026-07-31T10:16:00Z"
  },
  "assistantMessage": {
    "id": "uuid-asst-msg",
    "role": "assistant",
    "content": "Here are a few waterproof hiking shoes under $120 that match what you asked for.",
    "sequenceNumber": 4,
    "createdAt": "2026-07-31T10:16:08Z",
    "products": []
  }
}
```

`mode` values the UI should handle:

| `mode` | Meaning | UI hint |
| --- | --- | --- |
| `recommend` | Products found | Show `reply` + product cards |
| `clarify` | Need more info | Show `reply` and/or `followUpQuestion`; `products` may be `[]` |
| `info` | Informational, no catalog hit needed | Show `reply` |
| `other` | Fallback / off-script shopping edge | Show `reply` |

**Clarify example**

```json
{
  "sessionId": "uuid-session",
  "conversationId": "uuid-conversation",
  "mode": "clarify",
  "reply": "I can help with hiking shoes — what's your budget?",
  "followUpQuestion": "What's your budget?",
  "products": [],
  "userMessage": { "...": "..." },
  "assistantMessage": { "...": "..." }
}
```

**Rejection (regex or validation Call #1)** — message was **not** saved to history

**Response `422`**

```json
{
  "error": "I can't help with that request. Please ask about shopping or products.",
  "code": "REJECTED"
}
```

Frontend: show the error in the UI; **do not** append the user's text as a permanent chat bubble if you only optimistically added it — or roll the optimistic bubble back. Prefer: keep the input, show a toast/inline error.

**Other errors**

| Status | `code` (example) | When |
| --- | --- | --- |
| `401` | `UNAUTHORIZED` | Missing/invalid JWT |
| `403` | `FORBIDDEN` | Session not owned by this user |
| `404` | `SESSION_NOT_FOUND` | Bad `sessionId` |
| `500` | `ASSISTANT_FAILED` | Call #2 / pipeline failed after validation |
| `503` | `UPSTREAM_UNAVAILABLE` | LLM or DB temporarily down |

```json
{
  "error": "Something went wrong, please try again.",
  "code": "ASSISTANT_FAILED"
}
```

---

## Catalog (optional for UI / debug)

### `GET /api/products`

**Auth:** required (or public for debug — treat as **required** unless backend says otherwise)

**Query (optional):**

```
GET /api/products?q=hiking&budget=120&limit=20
```

**Response `200`**

```json
{
  "products": [ /* Product[] */ ]
}
```

The main shopping flow does **not** need this route — recommendations come back on the message response. Useful for admin/debug screens.

---

## Health

### `GET /health`

No auth.

**Response `200`**

```json
{ "status": "ok" }
```

---

## Frontend integration checklist

1. **Base client** — `fetch(`${import.meta.env.VITE_API_URL}${path}`, { headers })`.
2. **Attach JWT** on every call except register / verify-email / login / health.
3. **On 401** — clear token, redirect to login.
4. **State to keep in the app:**
   - `token`, `user`
   - open `conversationId`, `sessionId`
   - `messages[]` for the open thread
   - `conversations[]` for the sidebar
5. **Optimistic UI:** you may show the user bubble immediately on send; on `422` / `5xx`, remove it or mark it failed.
6. **Mocking:** until backend ships a route, return the sample JSON above from a stub in `frontend/src/api/` behind a `USE_MOCK_API` flag.
7. **Auth flow:** register does not store a JWT; only login does. After signup, complete `verify-email` before login.

---

## Endpoint map (quick)

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| `POST` | `/api/auth/register` | No | Create account; email verification required |
| `GET` | `/api/auth/verify-email` | No | Activate account via token |
| `POST` | `/api/auth/login` | No | Login + JWT (verified accounts only) |
| `POST` | `/api/conversations` | Yes | New chat → `sessionId` |
| `GET` | `/api/conversations` | Yes | Sidebar list |
| `POST` | `/api/conversations/{id}/resume` | Yes | Resume → new `sessionId` + messages |
| `PATCH` | `/api/conversations/{id}` | Yes | Rename |
| `DELETE` | `/api/conversations/{id}` | Yes | Hard delete |
| `POST` | `/api/sessions/{sessionId}/messages` | Yes | Send message / get reply + products |
| `GET` | `/api/products` | Yes* | Catalog debug |
| `GET` | `/health` | No | Health check |

\*Confirm with backend if products stays public for local debug.

---

## See also

- [`ARCHITECTURE.md`](ARCHITECTURE.md) — why `sessionId` ≠ `conversationId`, security pipeline, ownership rules, auth endpoints
- [`authPlan.md`](authPlan.md) — sequenced auth implementation plan
- [`database-schema.md`](database-schema.md) — persistence behind these shapes
- [`project-plan.md`](project-plan.md) — sprint ownership / timeline
