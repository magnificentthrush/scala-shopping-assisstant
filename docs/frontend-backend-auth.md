# Frontend ↔ Backend auth guide

How the React app talks to the Scala API, where requests are made, and how tokens are stored.

## Big picture

```
UI page (Signup / Login / VerifyEmail)
        │
        ▼
api/auth.ts  (or VerifyEmail calling apiFetch directly)
        │
        ▼
api/client.ts  →  fetch(BASE_URL + path)
        │
        ▼
Scala backend  (http://localhost:8080 by default)
```

- **Base URL:** `import.meta.env.VITE_API_URL` or fallback `http://localhost:8080`
  (`frontend/src/api/client.ts`)
- **Transport:** browser `fetch` (not axios for auth)
- **Auth APIs are public:** paths starting with `/api/auth` do **not** send the JWT

---

## Shared HTTP helper

**File:** `frontend/src/api/client.ts`  
**Function:** `apiFetch(path, options?)`

What it does:

1. Builds URL: `BASE_URL + path`
2. Sets `Content-Type: application/json` only when there is a `body` (avoids bad CORS preflight on GET)
3. If `localStorage.token` exists **and** the path is **not** `/api/auth…` or `/health`, adds:
   `Authorization: Bearer <jwt>`
4. On success: parses JSON and returns it
5. On error: throws `ApiError` with `message`, `status`, and optional `code`
6. On `401`: clears `token` and `user` from `localStorage`

---

## Two kinds of tokens

| Token | Purpose | Where stored | When set | When cleared |
| --- | --- | --- | --- | --- |
| **JWT** (`token`) | Proves the user is logged in for protected APIs | `localStorage["token"]` | After successful **login** | Logout, or any `401` from `apiFetch` |
| **Verification token** (`pendingVerificationToken`) | One-time email verify (Phase 1 when Resend is off) | `localStorage["pendingVerificationToken"]` | After **register** if backend returns `verificationToken` | After successful verify |

Also stored after login: `localStorage["user"]` = JSON `{ id, fullName, email }`.

Register does **not** save a JWT. User is not logged in until verify + login.

---

## Auth API calls (who → what → response)

### 1. Register

| | |
| --- | --- |
| **UI** | `Signup.tsx` → `handleSubmit` |
| **API fn** | `register(...)` in `frontend/src/api/auth.ts` |
| **Request** | `POST /api/auth/register` |
| **Body** | `{ fullName, email, password }` |
| **Success (201)** | `{ user: { id, fullName, email }, needsVerification: true, verificationToken?: string }` |
| **Frontend after** | If `verificationToken` present → save to `pendingVerificationToken`. Show “Check your email”. **No** JWT saved. |

Common errors: `400` weak password, `409 EMAIL_TAKEN`.

---

### 2. Verify email

| | |
| --- | --- |
| **UI** | `VerifyEmail.tsx` → `useEffect` → `verifyEmailToken(token)` |
| **API call** | `apiFetch` from `VerifyEmail.tsx` (not wrapped in `auth.ts`) |
| **Request** | `GET /api/auth/verify-email?token=...` |
| **Token source** | URL `?token=` (email link), else `localStorage.pendingVerificationToken` |
| **Success (200)** | `{ verified: true }` |
| **Frontend after** | Clear `pendingVerificationToken`; show success → user goes to login |

Common errors: `400 TOKEN_INVALID`, `400 TOKEN_EXPIRED`.

---

### 3. Login

| | |
| --- | --- |
| **UI** | `Login.tsx` → `handleSubmit` |
| **API fn** | `login(...)` in `frontend/src/api/auth.ts` |
| **Request** | `POST /api/auth/login` |
| **Body** | `{ email, password }` |
| **Success (200)** | `{ user: { id, fullName, email }, token: "<jwt>" }` |
| **Frontend after** | `saveAuth(user, token)` → `localStorage`; `AuthContext.setUser(user)`; navigate to `/` |

Common errors:

- `401 INVALID_CREDENTIALS`
- `403 EMAIL_NOT_VERIFIED` → Login shows a special “please verify” message (and Phase-1 **Verify now** if a pending token exists)

---

### 4. Logout

| | |
| --- | --- |
| **UI** | Settings / sidebar via `useAuth().logout` |
| **API fn** | `logout()` in `auth.ts` (local only — no backend call) |
| **Effect** | Removes `token` and `user` from `localStorage`; clears React auth state |

---

## How “logged in” works in the UI

**File:** `frontend/src/context/AuthContext.tsx`

- On app load: `restoreSession()` reads `user` from `localStorage` (does not re-check the JWT with the server)
- `isLoggedIn` = whether that user object exists
- `ProtectedRoute` redirects to `/login` if not logged in

**File:** `frontend/src/components/ProtectedRoute/ProtectedRoute.tsx`

- Wraps private routes (e.g. chat home `/`)

---

## Calling protected APIs later

Any non-auth call goes through `apiFetch` too, e.g.:

- `frontend/src/api/conversations.ts`
- `frontend/src/api/chat.ts`

Those paths are **not** under `/api/auth`, so `apiFetch` automatically attaches:

```http
Authorization: Bearer <jwt from localStorage>
```

If the JWT is missing/expired, backend returns `401` and the client clears storage.

---

## End-to-end happy path

1. **Signup** → `POST /register` → maybe store verify token → “check email”
2. **Verify** → `GET /verify-email?token=...` → email marked verified
3. **Login** → `POST /login` → store JWT + user → land on `/`
4. **Later requests** → `apiFetch` adds Bearer JWT automatically

---

## Quick file map

| File | Role |
| --- | --- |
| `frontend/src/api/client.ts` | Base URL, `fetch`, Bearer header, errors |
| `frontend/src/api/auth.ts` | `register`, `login`, `logout`, token/user storage helpers |
| `frontend/src/pages/Signup/Signup.tsx` | Calls `register` |
| `frontend/src/pages/Login/Login.tsx` | Calls `login`, handles `EMAIL_NOT_VERIFIED` |
| `frontend/src/pages/VerifyEmail/VerifyEmail.tsx` | Calls verify-email GET |
| `frontend/src/context/AuthContext.tsx` | React login state from `localStorage` |
| `frontend/src/components/ProtectedRoute/ProtectedRoute.tsx` | Gate for private pages |

See also: [API_CONTRACT.md](API_CONTRACT.md), [authPlan.md](authPlan.md).
