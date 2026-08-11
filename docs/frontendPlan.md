# Frontend Plan — ShopPilot Chat UI → Real Backend

Plan for flipping the frontend from mock/localStorage to the live Scala backend, filling UI gaps, and making the chat experience production-ready.

**What already works (no changes needed):**
- Auth flow (register → verify → login → JWT in localStorage) — fully wired to real API
- Theme system (dark/light via `data-theme`, persisted in localStorage)
- Component shell (Sidebar, ChatWidget, ProductCard, Input, Settings, Navbar)
- `apiFetch` client with JWT injection, error parsing, 401 handling

**What's broken or missing when mocks flip off:**
- `USE_MOCK_API = true` in both `chat.ts` and `conversations.ts`
- `Chat.tsx` blocks on `!conversationId || !sessionId` — but backend returns `conversationId: null` on new chat until first message
- `sendMessage` mock never sets `conversationId` from response
- `mode` / `followUpQuestion` typed but never rendered
- `userMessage` / `assistantMessage` from API response ignored (optimistic IDs diverge from DB)
- Product cards never populated (mock always returns `[]`)
- Price display uses `$` but backend returns INR (₹)
- Error handling is generic — no distinction between 422 REJECTED, 400, 500, 503
- Rename: no blank-title guard on frontend (backend now returns 400)
- Attachment UI exists but backend doesn't support files — needs removal or disclaimer
- Message actions (copy/like/dislike/TTS/regen) are decorative stubs

---

## 1. Types & Contracts

### 1.1 Fix `StartConversationResponse` type

```typescript
// chat.ts
interface StartConversationResponse {
  conversationId: string | null;  // null until first message
  sessionId: string;
  title: string | null;
  messages: Message[];
}
```

### 1.2 Fix `User` interface duplication in `types/index.ts`

Merge both declarations into one with optional `avatarUrl`:

```typescript
export interface User {
  id: string;
  fullName: string;
  email: string;
  avatarUrl?: string;
}
```

### 1.3 Add `ApiError` code constants

```typescript
// types/index.ts or api/client.ts
export const ErrorCodes = {
  REJECTED: "REJECTED",
  BLANK_TITLE: "BLANK_TITLE",
  SESSION_NOT_FOUND: "SESSION_NOT_FOUND",
  FORBIDDEN: "FORBIDDEN",
  ASSISTANT_FAILED: "ASSISTANT_FAILED",
  UPSTREAM_UNAVAILABLE: "UPSTREAM_UNAVAILABLE",
} as const;
```

---

## 2. API Layer — Flip to Real

### 2.1 Create shared mock flag

```typescript
// api/config.ts
export const USE_MOCK_API = import.meta.env.VITE_USE_MOCK_API === "true" || false;
```

### 2.2 Update `chat.ts` — real path only changes

- `startConversation` — real path returns `conversationId: null`, keep as-is
- `sendMessage` — **remove unused `conversationId` param** (backend only needs `sessionId` in URL), use `res.conversationId` from response to update state

### 2.3 Update `conversations.ts` — real path only changes

- All functions already have real paths — just flip the flag
- `resumeConversation` returns `{ conversationId, sessionId, title, messages }` — matches backend

### 2.4 Add error handling wrapper

```typescript
// api/errors.ts
export function getErrorMessage(err: unknown): string {
  if (err instanceof ApiError) {
    switch (err.code) {
      case "REJECTED": return "I can't help with that request. Please ask about shopping or products.";
      case "BLANK_TITLE": return "Title cannot be blank.";
      case "SESSION_NOT_FOUND": return "Session expired. Please start a new chat.";
      case "ASSISTANT_FAILED": return "Assistant failed to respond. Please try again.";
      case "UPSTREAM_UNAVAILABLE": return "Service temporarily unavailable. Please try again.";
      default: return err.message;
    }
  }
  return "Something went wrong. Please try again.";
}
```

---

## 3. Chat State Management — Fix the Null Conversation Flow

### 3.1 `Chat.tsx` — allow null `conversationId`

**Current (broken):**
```typescript
if (!conversationId || !sessionId) return <Loading />;
```

**Fixed:**
```typescript
if (!sessionId) return <Loading />;
```

`conversationId` is null on new chat — that's valid. The chat renders with empty messages and a `sessionId`.

### 3.2 `ChatWidget.tsx` — handle first message → real `conversationId`

```typescript
async function handleSend(file: File | null) {
  // ... existing optimistic message logic ...

  try {
    const res = await sendMessage(sessionId, input.trim());
    
    // On first message, backend lazy-creates the conversation
    // Update parent state so sidebar shows the new conversation
    if (!conversationId && res.conversationId) {
      onConversationCreated(res.conversationId);
    }

    // Replace optimistic user message with real one from DB
    setMessages((prev) => {
      const withoutOptimistic = prev.filter((m) => m.id !== optimisticUserMessage.id);
      return [
        ...withoutOptimistic,
        { ...res.userMessage, attachedImageUrl },
        { ...res.assistantMessage, products: res.products },
      ];
    });

    if (wasFirstMessage) onFirstMessageSent();
  } catch (err) {
    // Remove optimistic message on error
    setMessages((prev) => prev.filter((m) => m.id !== optimisticUserMessage.id));
    // Show error toast/inline
  }
}
```

### 3.3 `Chat.tsx` — pass `onConversationCreated` callback

```typescript
<ChatWidget
  conversationId={conversationId}
  sessionId={sessionId}
  onConversationCreated={(id) => {
    setConversationId(id);
    setSidebarRefreshKey((k) => k + 1);  // refresh sidebar to show new convo
  }}
  onFirstMessageSent={() => setSidebarRefreshKey((k) => k + 1)}
/>
```

### 3.4 `ChatWidget` props update

```typescript
interface ChatWidgetProps {
  conversationId: string | null;  // was: string
  sessionId: string;
  onConversationCreated: (id: string) => void;  // new
  onFirstMessageSent: () => void;
}
```

---

## 4. Render `mode` and `followUpQuestion`

### 4.1 Add `mode` to message type

```typescript
// types/index.ts
export interface Message extends ConversationTurn {
  id: string;
  sequenceNumber: number;
  createdAt: string;
  products?: Product[];
  mode?: "recommend" | "clarify" | "info" | "other";  // on assistant messages
  followUpQuestion?: string;
}
```

### 4.2 `ChatWidget` — store mode on assistant messages

```typescript
setMessages((prev) => [
  ...prev,
  { ...res.userMessage, attachedImageUrl },
  {
    ...res.assistantMessage,
    products: res.products,
    mode: res.mode,
    followUpQuestion: res.followUpQuestion,
  },
]);
```

### 4.3 Render `followUpQuestion` as a suggested action

```tsx
{msg.mode === "clarify" && msg.followUpQuestion && (
  <button
    className="follow-up-chip"
    onClick={() => {
      setInput(msg.followUpQuestion!);
      // focus input
    }}
  >
    {msg.followUpQuestion}
  </button>
)}
```

### 4.4 Render `mode` badge on assistant messages (subtle)

```tsx
{msg.mode && msg.mode !== "other" && (
  <span className={`mode-badge mode-badge--${msg.mode}`}>
    {msg.mode === "recommend" ? "Products" : msg.mode === "clarify" ? "Question" : "Info"}
  </span>
)}
```

---

## 5. Product Display — INR Currency

### 5.1 `ProductCard` — format price as INR

```tsx
function formatInr(amount: number): string {
  return new Intl.NumberFormat("en-IN", {
    style: "currency",
    currency: "INR",
    maximumFractionDigits: 0,
  }).format(amount);
}

// In render:
<span className="product-card__price">{formatInr(product.price)}</span>
{hasDiscount && (
  <span className="product-card__original">{formatInr(product.originalPrice)}</span>
)}
```

### 5.2 Add discount percentage badge

```tsx
{hasDiscount && (
  <span className="product-card__discount">
    {Math.round((1 - product.price / product.originalPrice) * 100)}% off
  </span>
)}
```

---

## 6. Error Handling — User-Facing Messages

### 6.1 `ChatWidget` — typed error display

```typescript
catch (err) {
  const message = getErrorMessage(err);
  setMessages((prev) => prev.filter((m) => m.id !== optimisticUserMessage.id));
  setError(message);  // Show as toast or inline banner
  setInput(input);    // Restore the user's message so they don't lose it
}
```

### 6.2 Add error state to ChatWidget

```typescript
const [error, setError] = useState<string | null>(null);

// Auto-dismiss after 5s
useEffect(() => {
  if (!error) return;
  const timer = setTimeout(() => setError(null), 5000);
  return () => clearTimeout(timer);
}, [error]);
```

### 6.3 Render error banner

```tsx
{error && (
  <div className="chat-error" role="alert">
    <span>{error}</span>
    <button onClick={() => setError(null)} aria-label="Dismiss error">
      <X size={15} />
    </button>
  </div>
)}
```

### 6.4 `Sidebar` — handle rename errors

```typescript
async function saveEdit(id: string) {
  const trimmed = editTitle.trim();
  if (!trimmed) {
    setEditingId(null);  // Just cancel — backend rejects blank
    return;
  }
  try {
    await renameConversation(id, trimmed);
    await loadConversations();
  } catch (err) {
    // Show error — maybe a toast
    console.error("Rename failed:", getErrorMessage(err));
  }
  setEditingId(null);
}
```

---

## 7. Remove/Disable Attachment UI

Backend doesn't support file uploads. Two options:

### Option A: Remove attachment buttons (cleaner)

Remove the `Plus` button and attachment menu from `Input.tsx`. Keep the text input and send button only.

### Option B: Disable with tooltip (less destructive)

Keep buttons but disable them with `title="Coming soon"`. The backend has no file upload endpoint.

**Recommendation:** Option A — remove. Cleaner UI, no false affordances.

---

## 8. Message Actions — Implement or Remove

### 8.1 Copy (already works)

Keep as-is — `navigator.clipboard.writeText` works.

### 8.2 Regenerate

Backend has no regenerate endpoint yet (marked as future work in `call2Plan.md`). Options:
- **Hide the button** until backend implements it
- **Disable with tooltip** "Coming soon"

### 8.3 Like/Dislike, TTS

No backend support. **Hide buttons** or keep as disabled placeholders.

**Recommendation:** Hide all except Copy. Less visual noise.

---

## 9. Settings — Display Name

Currently saves to localStorage only. No backend endpoint exists for updating user profile.

**Options:**
- **Keep local-only** — acceptable for MVP, add backend endpoint later
- **Add backend endpoint** — `PATCH /api/users/me` (out of scope for this plan)

**Recommendation:** Keep local-only for now. Add a note: "Name is saved locally."

---

## 10. Sidebar — Auto-Title Display

Backend now auto-generates titles from Call #2 filters (e.g., "Hiking shoes · under ₹9,960"). Frontend already handles this correctly:

```tsx
{convo.title || "New chat"}
```

No changes needed — the backend fills `title` when it's no longer null.

---

## 11. Sequenced Task List

Build in this order — each task is independently testable:

### Task 1: Fix types and shared config
- [ ] Fix `StartConversationResponse.conversationId` → `string | null`
- [ ] Merge duplicate `User` interfaces in `types/index.ts`
- [ ] Create `api/config.ts` with `USE_MOCK_API` flag
- [ ] Create `api/errors.ts` with `getErrorMessage`
- [ ] Add `mode` and `followUpQuestion` to `Message` type

### Task 2: Flip API layer to real backend
- [ ] Update `chat.ts` — remove `conversationId` param from `sendMessage`, use response
- [ ] Update `conversations.ts` — no changes needed (real paths already exist)
- [ ] Set `USE_MOCK_API = false` (or env-driven)

### Task 3: Fix null-conversation flow in Chat
- [ ] Update `Chat.tsx` — render on `sessionId` alone, not `conversationId`
- [ ] Add `onConversationCreated` callback
- [ ] Update `ChatWidgetProps` — `conversationId: string | null`, add `onConversationCreated`

### Task 4: Fix ChatWidget message handling
- [ ] Replace optimistic messages with real `userMessage`/`assistantMessage` from API
- [ ] Store `mode` and `followUpQuestion` on assistant messages
- [ ] Handle first message → set `conversationId` via callback
- [ ] Roll back optimistic message on error

### Task 5: Render mode and followUpQuestion
- [ ] Add `follow-up-chip` component for clarify mode
- [ ] Add `mode-badge` on assistant messages
- [ ] Style both in `ui.css`

### Task 6: Product cards — INR formatting
- [ ] Add `formatInr` helper
- [ ] Update `ProductCard` to use INR
- [ ] Add discount percentage badge

### Task 7: Error handling
- [ ] Add `error` state to `ChatWidget`
- [ ] Render error banner with dismiss
- [ ] Auto-dismiss after 5s
- [ ] Restore input on error
- [ ] Handle rename errors in Sidebar

### Task 8: Clean up attachment UI
- [ ] Remove `Plus` button and attachment menu from `Input.tsx`
- [ ] Remove `attachedFile` state and related handlers
- [ ] Remove `attachedImageUrl` from `ChatMessage` and rendering

### Task 9: Clean up message actions
- [ ] Hide like/dislike/TTS/regen buttons (keep Copy only)
- [ ] Or add `disabled` + tooltip "Coming soon"

### Task 10: End-to-end smoke test
- [ ] New chat → send message → verify conversation appears in sidebar with auto-title
- [ ] Send "I need waterproof hiking shoes under $120" → verify products render with INR prices
- [ ] Send clarify-mode message → verify follow-up chip appears
- [ ] Rename conversation → verify title updates
- [ ] Try blank rename → verify error or silent cancel
- [ ] Delete conversation → verify removed from sidebar
- [ ] Resume conversation → verify history loads
- [ ] Logout → verify redirect to login
- [ ] Login → verify redirected back to chat with history

---

## 12. Sequence Diagram — Real API Flow

```
User                Frontend              Backend               Supabase
 |                      |                     |                     |
 |-- New Chat --------->|                     |                     |
 |                      |-- POST /api/conversations ------------->|
 |                      |                     |-- insert chat_session ->|
 |                      |<-- 201 { sessionId, conversationId: null }   |
 |<-- render empty chat |                     |                     |
 |                      |                     |                     |
 |-- "I need shoes" --->|                     |                     |
 |                      |-- POST /api/sessions/{sid}/messages ---->|
 |                      |                     |-- Call #1 (validate)  |
 |                      |                     |-- lazy-create convo   |
 |                      |                     |-- insert user msg     |
 |                      |                     |-- Call #2 (assistant) |
 |                      |                     |-- search products     |
 |                      |                     |-- commit assistant turn
 |                      |<-- 200 { conversationId, mode, reply,      |
 |                      |       products, userMessage, assistantMessage }
 |<-- render reply + products              |                     |
 |                      |                     |                     |
 |                      |-- (sidebar refresh) |                     |
 |                      |-- GET /api/conversations ---------------->|
 |                      |<-- 200 { conversations: [...] }           |
 |<-- sidebar shows "Hiking shoes · under ₹9,960"                  |
```

---

## 13. Environment Variables

| Variable | Purpose | Default |
|----------|---------|---------|
| `VITE_API_URL` | Backend base URL | `http://localhost:8080` |
| `VITE_USE_MOCK_API` | Enable mock mode | `false` |

---

## 14. Follow-ups (Not This Pass)

- **Regenerate endpoint** — backend needs `POST /api/sessions/{sid}/messages/{mid}/regenerate`
- **User profile update** — backend needs `PATCH /api/users/me`
- **File/image upload** — backend needs upload endpoint + storage
- **SSE streaming** — real-time token streaming for assistant replies
- **Message feedback** — like/dislike persistence
- **Conversation search** — full-text search over message content
