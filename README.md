# Scala AI Shopping Assistant

An AI-powered shopping assistant built as a 2-week internship project. Users describe what they want in plain language (e.g. *"waterproof hiking shoes under $120"*), and the app finds matching products and explains why they fit.

**Stack:** React (frontend) · Scala / Cask (backend) · Gemma 4 via Google AI Studio · PostgreSQL

**Goal:** A working, demoable MVP — not a production system.

---

## How it works (short version)

1. The user chats in the React UI.
2. The Scala backend asks Gemma 4 to turn the message into structured filters (category, budget, attributes).
3. PostgreSQL retrieves matching products (full-text search + filters → top 30).
4. A reranker picks the best 5.
5. Gemma 4 writes a short explanation; the UI shows the reply plus product cards.

---



## Repository structure

```
scala-shopping-assistant/
├── backend/                          # Scala API (Cask + uPickle)
│   ├── project/                      # sbt project settings
│   └── src/
│       ├── main/scala/assistant/
│       │   ├── http/                 # routes / controllers
│       │   ├── domain/               # case classes (Product, ConversationTurn, etc.)
│       │   ├── services/             # LLM, intent, catalog, reranker
│       │   ├── repo/                 # database access
│       │   └── config/               # app configuration
│       └── test/scala/assistant/     # backend tests
├── frontend/                         # React + TypeScript UI
│   └── src/
│       ├── components/ChatWidget/    # chat UI
│       ├── components/ProductCard/   # product cards
│       └── api/                      # calls to the Scala backend
├── data/
│   ├── raw/                          # original Kaggle CSV (gitignored)
│   └── scripts/                      # seed script → Postgres
├── docs/
│   └── project-plan.md               # full sprint plan & decisions
└── README.md
```


| Folder      | Who owns it            | What lives here                                |
| ----------- | ---------------------- | ---------------------------------------------- |
| `backend/`  | Backend interns + lead | HTTP routes, services, DB access, LLM client   |
| `frontend/` | Frontend intern        | Chat widget, product cards, API client         |
| `data/`     | Catalog owner          | Product CSV + seed scripts                     |
| `docs/`     | Whole team             | Architecture notes, API contract, project plan |


---



## Architecture

```
                CLIENT (React)
                      │
            1. User clicks Search
                      │
                      ▼
        HTTP Request (JSON over the Internet)
                      │
                      ▼
               Cask HTTP Server
                      │
        2. Receives POST /search
                      │
                      ▼
          Routing & Endpoint Selection
                      │
                      ▼
        JSON Deserialization (uPickle)
                      │
                      ▼
        SearchRequest Scala Object
                      │
                      ▼
        Business Logic / Service Layer
                      │
        ┌─────────────┴─────────────┐
        │                           │
        ▼                           ▼
     LLM Service              Database Service
        │                           │
        │                           ▼
        │                     PostgreSQL
        │                           │
        └─────────────┬─────────────┘
                      ▼
          SearchResponse Scala Object
                      │
        JSON Serialization (uPickle)
                      │
                      ▼
          HTTP Response (JSON)
                      │
                      ▼
                  React UI
```



### Step-by-step (plain English)

1. **Client (React)**
  The browser app the user sees — chat box, send button, product cards.
2. **User clicks Search**
  The user types a query (or sends a chat message) and hits search/send. React packages that into data ready to send.
3. **HTTP request (JSON over the Internet)**
  The frontend sends a POST request with a JSON body (the search text / conversation message) to the Scala backend.
4. **Cask HTTP server**
  Cask is our lightweight Scala web framework. It listens for incoming HTTP requests — like a receptionist for the backend.
5. **Receives** `POST /search`
  The server accepts the request on the search endpoint. (In the full chat flow this is similar to `POST /api/conversations/.../messages` — same idea: “here’s what the user said, give me a reply.”)
6. **Routing & endpoint selection**
  Cask looks at the URL and HTTP method and picks the right handler function. Wrong path → 404; right path → our search/chat logic runs.
7. **JSON deserialization (uPickle)**
  Raw JSON text is turned into a real Scala object. uPickle does this conversion so the rest of the code can work with typed fields instead of strings.
8. `SearchRequest` **Scala object**
  A case class holding the request data (query text, filters, conversation id, etc.). From here on, Scala code uses this object, not raw JSON.
9. **Business logic / service layer**
  The “brain” of the request. Controllers stay thin; services decide what to do — call the LLM, query the DB, rerank results, build the reply.
10. **LLM service**
  Talks to Gemma 4 (Google AI Studio). Used to extract intent/filters from the user’s words and later to explain why the top products match.
11. **Database service → PostgreSQL**
  Runs full-text search and SQL filters against the `products` table. Returns candidate products (e.g. top 30) for the service layer to refine.
12. `SearchResponse` **Scala object**
  After LLM + DB work finishes, results are assembled into a response case class (reply text, product list, optional follow-up question).
13. **JSON serialization (uPickle)**
  The Scala response object is turned back into JSON text so the frontend can read it.
14. **HTTP response (JSON)**
  Cask sends that JSON back over HTTP to the browser.
15. **React UI**
  The frontend parses the JSON and updates the screen — chat bubble + product cards for the user.

**One-line mental model:** React talks only to Scala; Scala talks to Gemma 4 and Postgres. The frontend never touches the database or the LLM API directly.

---



## Tech choices (quick reference)


| Layer        | Choice                                           |
| ------------ | ------------------------------------------------ |
| Frontend     | React + TypeScript                               |
| Backend HTTP | Cask                                             |
| JSON         | uPickle                                          |
| Database     | PostgreSQL (Docker Compose)                      |
| LLM          | Gemma 4 via Google AI Studio                     |
| Catalog      | Kaggle e-commerce dataset → seeded into Postgres |


See `[docs/project-plan.md](docs/project-plan.md)` for the full plan, API ideas, timeline, and team roles.