# Changelog

All notable changes to the FTSM E-Commerce Platform.
Format: date + semantic version, grouped into Added / Changed / Fixed / Verified.

---

## [v0.6.0] — 2026-07-22 — Multimodal vision listing, UI overhaul & pre-demo QA pass

Lands the third AI innovation point (deferred in v0.5.0), a full front-end visual
redesign, and a browser-based UI/UX test pass run the day before the live demo.
The QA pass drove all fixes in the **Fixed** section below — each was reproduced in a
real browser and re-verified after the fix (see `docs/DEMO_UI_TEST_REPORT.md`).

### Added
- **Innovation 3 — multimodal AI listing draft (now live via GPT-4o Vision).** New
  `service/ListingDraftService` + `POST /api/items/draft-from-image`: a student uploads a
  photo, the image is base64-encoded server-side and sent to GPT-4o, and the model returns
  a pre-filled draft (title, description, category, condition, suggested RM price) the
  student can edit before publishing. `SellItem` gains an "AI fill from photo" action.
  (v0.5.0 deferred this because DeepSeek rejects image input; implemented against OpenAI.)
- **Front-end visual redesign**: new dark `Landing` page, cursor/scroll effect layer
  (`components/fx/` — Aurora background, spotlight, crosshair, scroll-reveal), a `Profile`
  page, and a restyle of the whole app (navbar, cards, forms, marketplace, seckill, chat).
- `docs/DEMO_UI_TEST_REPORT.md`: full pre-demo test matrix, findings, and a morning
  smoke-test checklist.

### Changed
- Build config migrated to CommonJS (`postcss.config.cjs`, `tailwind.config.cjs`) to fix a
  Node 24 + ESM PostCSS hang; removed the old `.js` configs.
- `ChatService` system prompt now instructs the assistant to answer in the user's language
  and to avoid markdown tables/headings/code blocks (which rendered as raw text in the bubble).

### Fixed (pre-demo QA — all browser-reproduced & re-verified)
- **🔴 Cannot publish a listing after uploading a local image** (`ImageUpload.tsx`): the
  "paste URL" field was `<input type="url">`, so an uploaded image's relative path
  (`/uploads/xxx.jpg`) failed native URL validation and blocked the *entire* form from
  submitting ("Please enter a URL"). Affected **both** student listings (`SellItem`) and
  admin product creation (`AdminDashboard`), which share the component. Changed to
  `type="text"` + `inputMode="url"`. End-to-end re-verified: item created with relative image URL.
- **🔴 Broken product images**: `app.upload.dir` is resolved relative to the working
  directory, so images 404/500 when the backend is started from `backend/`. Consolidated all
  files into the repo-root `uploads/` and documented starting with an absolute `UPLOAD_DIR`
  (permanent fix options tracked in the report).
- Auth error messages were brand-blue and did not read as errors → added `--danger` (#dc2626);
  `Login`/`Register`/`OtpVerify` now show errors in red.
- Marketplace "AI Search" toggle had a tiny (32×16px) hit area; clicking the label text did
  nothing → click handler moved to the whole `<label>`.
- Chat bubbles showed raw markdown `**` and `|table|` syntax → added minimal safe inline
  rendering for `**bold**`/`` `code` `` (no `dangerouslySetInnerHTML`).
- Order detail exposed the internal enum `FAKE_WALLET` → mapped to "Campus Wallet".
- Landing hero headline overflowed and was clipped on mobile → responsive font sizing.
- Footer year "FYP 2025" was inconsistent with the Landing page → unified to 2026.

### Verified
- Full UI walkthrough (desktop 1280×720 + mobile 375×812) with AI features live (DeepSeek
  chat + semantic search, GPT-4o vision listing): 29 checkpoints pass, console clean.

---

## [v0.5.0] — 2026-06-10 — AI innovation points: agentic assistant + semantic search

Authored by Claude Code (Opus 4.8). Upgrades the platform's "shallow" AI into two
FYP innovation points (a third — multimodal vision listing — is deferred until a
vision-capable API is available; DeepSeek's cloud API rejects image input).

### Added — Backend
- **`service/llm/LlmClient`**: reusable wrapper over the OpenAI-compatible endpoint
  (reuses the existing `llmWebClient` bean + `app.llm.*` config). Shared by chat + search.
- **Innovation 1 — Agentic AI shopping assistant.** `ChatService` now drives an OpenAI
  function-calling loop (capped at 3 rounds). New `service/llm/ChatTools` exposes 4 tools
  the model can call against live data:
  - `search_products` (B2C), `search_items` (C2C), `get_seckill_status` (live Redis stock),
    `get_my_orders` (per-user, via `AuthPrincipal.userId()`).
  - Product/listing hits are returned as structured `ActionCard`s (≤6, de-duped) so the
    chat widget can offer one-click **Add to cart / View**.
- **Innovation 3 — LLM semantic search.** New `service/SmartSearchService` + endpoints
  `GET /api/items/smart-search` & `GET /api/products/smart-search`: keyword prefilter
  (≤50 candidates) → LLM reranks by intent → ordered results. Degrades to keyword results
  if the LLM is unavailable. (Public via existing GET permit rules — no security change.)

### Added — Frontend
- `ChatWidget`: renders assistant `ActionCard`s with Add-to-cart (wired to `useCartStore`)
  and View buttons.
- `Marketplace`: "🔍 Smart search" toggle — opt-in semantic search; keyword search stays
  the instant default.
- `types`/`api.ts`: `ActionCard`/`ChatReply` types; `itemApi.smartSearch`, `productApi.smartSearch`.

### Changed — Backend
- `ChatResponse` now carries an optional `actions` list (back-compat single-arg constructor kept).
- `ChatbotController` passes the authenticated `userId` through; ChatService runs synchronously
  (controller already blocked on the result).

### Verified
- `mvn test` (JDK 21) → **10/10 pass** (added `ChatToolsTest`, `SmartSearchServiceTest`).
- E2E through backend:
  - Chat "find cheap lanyards + any live flash sale" → tools fired → product-aware reply +
    1 action card, ~6.4s.
  - Chat "my recent orders" → `get_my_orders` fired → correct empty-state reply.
  - `products/smart-search?q=something to keep me warm on campus` → **FTSM Hoodie** (~2.2s),
    while keyword `?q=shirt` → empty. `q=cheapest thing I can buy` → price-ascending order,
    Test items dropped.
- **Build note:** must build with **JDK 21** (`JAVA_HOME=$(/usr/libexec/java_home -v 21)`);
  Lombok fails on the machine's default JDK 25 (`TypeTag UNKNOWN`).

---

## [v0.4.2] — 2026-06-01 — Default model → deepseek-v4-flash (faster)

- Benchmarked both DeepSeek v4 models (3 runs each, same product-context prompt):
  - `deepseek-v4-pro`: avg **~12.6s** (11.3–14.7s)
  - `deepseek-v4-flash`: avg **~4.9s** direct API / **~3s** through the backend
- Default `LLM_MODEL` changed `deepseek-v4-pro` → `deepseek-v4-flash`. ~3x faster with
  comparable answer quality for the shopping-assistant use case. Updated `.env`,
  `.env.example`, `application.yml`. (Switch back to `-pro` anytime via `LLM_MODEL`,
  no rebuild needed.)
- Verified through backend: `POST /api/chat` → 200 in ~3s, product-aware reply.

---

## [v0.4.1] — 2026-06-01 — Switch to deepseek-v4-pro (reasoning model)

- Default `LLM_MODEL` changed `deepseek-chat` → `deepseek-v4-pro` (user's provisioned model).
  Verified the model identifier against the live API (supported: `deepseek-v4-pro` /
  `deepseek-v4-flash`).
- `ChatService`: raised `max_tokens` 512 → 2048. `deepseek-v4-pro` is a **reasoning model**:
  its `reasoning_content` tokens are billed against `max_tokens` before the visible
  `content`, so a small budget returned an empty reply (`finish_reason=length`).
- Verified: `POST /api/chat` → 200 in ~8.4s, product-aware reply. (First call after boot
  can be slow due to macOS netty DNS cold-start; steady-state is fast.)

---

## [v0.4.0] — 2026-06-01 — AI chatbot live (DeepSeek)

Authored by Claude Code (Sonnet 4.6). Activates the AI shopping assistant using the
DeepSeek OpenAI-compatible API.

### Changed — Backend
- `WebClientConfig`: renamed bean `geminiWebClient` → `llmWebClient`; base URL now reads
  `app.llm.base-url` (default `https://api.deepseek.com/v1`).
- `ChatService`: replaced Gemini-specific request/response format with OpenAI-compatible
  format (`POST /chat/completions`, `messages` array with `system`/`user` roles,
  `choices[0].message.content` extraction). Injects `LLM_API_KEY` / `LLM_MODEL` env vars
  (default `deepseek-chat`). Product context still built via lightweight keyword retrieval.
- `ChatbotController`: changed return type from `Mono<ChatResponse>` → `ChatResponse`
  (blocks on the Mono). Root cause: Spring Security 6 `@EnableMethodSecurity` applies
  reactive authorization when a controller method returns `Mono<>`, conflicting with our
  servlet-based `JwtAuthFilter` (`SecurityContextHolder`) → POST /api/chat returned 403.
  Blocking at the controller level resolves the conflict; WebClient I/O in ChatService
  remains non-blocking.
- `application.yml`: renamed config block `app.gemini.*` → `app.llm.*`
  (`app.llm.base-url`, `app.llm.api-key`, `app.llm.model`).

### Changed — Config / Docs
- `.env.example`: replaced `GEMINI_*` vars with `LLM_API_KEY`, `LLM_BASE_URL`, `LLM_MODEL`.
- `.env` (local, gitignored): created with real DeepSeek key and base URL for dev use.

### Fixed
- `POST /api/chat` returning 403 with valid JWT: caused by `@EnableMethodSecurity` + `Mono<>`
  return type reactive/servlet security context mismatch. Fixed by blocking in controller.

### Verified — 2026-06-01
- `POST /api/chat` with valid JWT → 200; DeepSeek returns real product-aware AI reply.
- Reply includes product context (products fetched from DB and injected into prompt).
- `scripts/e2e_test.py --users 60 --stock 20` — **8/8 passing** (no regression).
  Note: e2e test reuses fixed email addresses (`student0-59@siswa.ukm.edu.my`); run
  `DELETE FROM users WHERE email LIKE '%@siswa.ukm.edu.my'` before each run on a
  persistent DB.

---

## [v0.3.0] — 2026-06-01 — P3: cart checkout + image upload

Authored by Claude Code (Sonnet 4.6). Builds directly on top of v0.2.0 (Codex).

### Added — Backend
- `UploadController` (`POST /api/upload`): authenticated multipart image upload, validates
  type (JPEG/PNG/WebP/GIF) and size (≤ 5 MB), stores to `uploads/` with UUID filename,
  returns `{"url": "/uploads/<uuid>.ext"}`. Rejects unauthenticated requests (403) and
  non-image types (400).
- `WebMvcConfig`: registers `/uploads/**` as a static resource handler pointing to the
  local `uploads/` directory (`file:<abs-path>/`). Uploaded images are served directly.
- `SecurityConfig`: added `GET /uploads/**` to public permit list so images load without auth.
- `application.yml`: added `app.upload.dir` (env: `UPLOAD_DIR`), moved `spring.servlet.multipart`
  limits (5 MB file / 6 MB request) to the correct `spring:` prefix.

### Added — Frontend
- `components/common/ImageUpload.tsx`: reusable image upload widget — file picker + preview +
  `POST /api/upload` call + URL fallback input. Used in SellItem and Admin product form.
- `pages/Cart.tsx` (`/cart`, protected): full shopping-cart checkout page — lists lines with
  remove, shows total, payment method selector (FAKE_WALLET / MOCK_FPX), places orders
  sequentially with per-line status, shows results and links to Orders.
- `services/api.ts`: added `uploadApi.image(file)`.
- `store/useCartStore.ts`: was already defined; now actively wired up in Marketplace and Navbar.
- `Navbar.tsx`: cart icon (🛒) with live badge showing line count, links to `/cart`. Only shown
  when authenticated.
- `features/marketplace/Marketplace.tsx`: added "Add to Cart" (🛒) button alongside direct Buy on
  each product/item card; triggers `cartAdd` + inline notice.
- `features/marketplace/ProductCard.tsx`: added optional `onAddToCart` prop; renders a small 🛒
  button next to the Buy button when provided.
- `features/marketplace/SellItem.tsx`: replaced plain `<Input label="Image URL">` with
  `<ImageUpload>` component.
- `features/admin/AdminDashboard.tsx`: replaced plain image URL input on the product form with
  `<ImageUpload>` component.
- `App.tsx`: added `/cart` route inside `<ProtectedRoute>`.

### Added — Infra
- `docker-compose.yml`: added `uploads_data` named volume mounted at `/app/uploads` in the
  backend container; added `UPLOAD_DIR=/app/uploads` env var.
- `frontend/nginx.conf`: added `/uploads/` reverse-proxy block so images load from the same
  public origin.
- `.gitignore`: added `uploads/` to exclude locally uploaded files.

### Fixed
- `WebMvcConfig` resource location was missing `file:` prefix and trailing `/` → `GET /uploads/**`
  returned 500. Fixed to `"file:" + absolutePath + "/"`.
- `spring.servlet.multipart` limits were nested under `app:` key in `application.yml` and silently
  ignored by Spring Boot. Moved to correct `spring:` section.

### Verified — 2026-06-01
- Backend package + frontend build: both clean (BUILD OK / 120 modules).
- **Upload tests (all pass):**
  - `POST /api/upload` with valid PNG → 200, `{"url":"/uploads/<uuid>.png"}`.
  - `GET /uploads/<uuid>.png` → 200 (file served correctly).
  - `POST /api/upload` with text file → 400 (type rejected).
  - `POST /api/upload` without JWT → 403 (auth enforced).
- `scripts/e2e_test.py --users 60 --stock 20` — **8/8 passing** (no regression).

---

## [v0.2.0] — 2026-05-31 — P1/P2 feature completion

Authored by Codex (GPT-5). This section covers the post-handoff implementation work requested
after Claude Code's v0.1.0 foundation.

### Added
- Admin SecKill event management: list, edit PENDING events, delete events, and clean Redis stock/bought keys.
- Order detail page at `/orders/:id` with pay-later support for `FAKE_WALLET` and `MOCK_FPX`.
- OTP resend endpoint (`POST /api/auth/resend-otp`) and frontend resend action.
- Review API and UI: post/list reviews, show averages, and require a paid product/item order before reviewing.
- Product and item detail pages with descriptions, images, reviews, and buy actions.
- Backend tests for UKM email validation, payment strategy resolution, and review repository persistence.

### Changed
- Marketplace cards now link to detail pages while keeping quick-buy behavior.
- README now documents real SMTP OTP setup and the new review flow.

### Verified
- `JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn test` — 5 tests passing.
- `npm run build` — frontend TypeScript and Vite production build passing.

### Verified by Claude Code (Sonnet 4.6) re-check — 2026-06-01
Claude re-verified the full v0.2.0 Codex delivery before accepting the commit:
- Backend 5/5 JUnit tests green; frontend production build clean (117 modules).
- `scripts/e2e_test.py --users 60 --stock 20` — **8/8 passing** against fresh MySQL + Redis (no
  regression from v0.1.0; concurrency guarantee still holds: 20 ACCEPTED / 40 SOLD_OUT / 0 oversell).
- New P1.1 endpoints smoke-tested: admin list/update/delete seckill events all return expected HTTP codes.
- P1.3 resend-OTP: returns 200 for an unverified account.
- P2.1 reviews guard: POST /api/reviews returns 400 before a paid order, 200 after.
- Git: `git remote -v` confirms `origin → https://github.com/feniturema/FYP.git`, `HEAD` in sync with `origin/main`.

---

## [v0.1.0] — 2026-05-31 — Initial scaffold + verified SecKill core

First version. Authored by Claude Code (Opus 4.8). Full project structure created, builds green,
and the high-concurrency critical path verified end-to-end against real MySQL + Redis.
Handoff to Codex for feature completion begins after this version (see `HANDOFF.md`).

### Added — Backend (Spring Boot 3.3 / Java 17, Maven)
- Project skeleton under `backend/` with `pom.xml` (Web, Data JPA, Data Redis, Security,
  Validation, Mail, WebFlux, Actuator, MySQL/H2, jjwt, springdoc, Lombok).
- Domain entities + repositories: `User`, `Item` (C2C), `Product` (B2C), `SeckillEvent`,
  `Order`, `Review`.
- Auth: `UkmEmailValidator` (regex `@(siswa.)?ukm.edu.my`), `JwtUtils`, `JwtAuthFilter`,
  `AuthPrincipal`, `AuthService` (register → OTP in Redis 5-min TTL → verify → JWT; login
  blocked until verified), `AuthController`.
- SecKill engine: `seckill_deduct.lua` (atomic stock decrement + per-user buy guard),
  `RedisConfig` (script + StringRedisTemplate), `SeckillService` (buy hot-path → Lua →
  `XADD seckill:orders` Redis Stream → 202 + tracking token; `@Scheduled` warms stock &
  flips PENDING→ACTIVE→ENDED), `SeckillStreamConsumer` (consumer group drains stream →
  persists orders idempotently), `SeckillController` (+ result polling).
- Orders + payment: `OrderService`, `OrderController`, Strategy pattern
  (`PaymentStrategy`, `FakeWalletStrategy`, `MockFpxStrategy`, `PaymentStrategyFactory`).
- B2C/C2C: `ProductService`/`ProductController`, `ItemService`/`ItemController` (owner-scoped).
- Admin: `AdminController` (create product, create seckill event).
- AI chatbot (stubbed): `ChatService` (async Gemini via `WebClient`, placeholder without key),
  `ChatbotController` (returns `Mono`).
- Cross-cutting: `SecurityConfig` (JWT, stateless, role rules), `CorsConfig`, `OpenApiConfig`
  (Swagger + bearer), `WebClientConfig`, `GlobalExceptionHandler`, custom exceptions,
  `RedisKeys`/`OtpUtils`, `DataSeeder` (admin `admin@ukm.edu.my`/`Admin@123` + sample products),
  `application.yml` (dev/h2/prod profiles, env-driven secrets), `Dockerfile`.

### Added — Frontend (Vite + React 18 + TypeScript)
- Project skeleton under `frontend/` with Vite/TS/Tailwind/PostCSS config, `/api` dev proxy.
- Tailwind theme with UKM maroon brand palette (`ukm.*`).
- Services: typed axios instance with JWT + 401 interceptors, `api.ts`
  (`authApi`, `itemApi`, `productApi`, `seckillApi`, `orderApi`, `chatApi`, `adminApi`).
- State: `useAuthStore` (persisted token+user), `useCartStore`.
- Shared UI: `Button`, `Input`, `Spinner`, `Navbar`, `Layout`, `ProtectedRoute`.
- Features: auth (Register → OtpVerify → Login), marketplace (Marketplace, ProductCard,
  SellItem), seckill (SeckillList, SeckillCard with `useCountdown` + result polling),
  chatbot (`ChatWidget`), admin (`AdminDashboard`), `pages/Orders`.
- Routing (`App.tsx`) with public/protected/admin routes; `Dockerfile` + `nginx.conf`.

### Added — Infra / tooling / docs
- Root `docker-compose.yml` (mysql + redis + backend + frontend/nginx), `.env.example`,
  `.gitignore`, `README.md`.
- `scripts/e2e_test.py` — reusable end-to-end + concurrency test harness.
- `HANDOFF.md` — full implementation handoff for Codex (conventions, prioritized backlog,
  gotchas, AI-on-hold note, git instructions).

### Fixed (found while booting against real MySQL + Redis)
- **MySQL reserved word**: `Item.condition` failed DDL → mapped to `@Column(name="item_condition")`.
- **Health DOWN**: actuator mail health indicator tried to reach SMTP with no creds →
  disabled via `management.health.mail.enabled=false` in `application.yml`.
- **Build toolchain**: Maven defaulted to Homebrew JDK 25 (Lombok incompatible,
  `TypeTag :: UNKNOWN`); pinned Lombok 1.18.36 + documented building with JDK 21.
- **Frontend TS config**: `tsconfig.node.json` emit setting + missing `@types/node` for
  `vite.config.ts`.

### Changed
- Decisions locked during planning: hybrid marketplace, mock payment (Strategy), shopping-
  assistant chatbot, email-OTP auth, Redis Stream for seckill async, basic admin, single-server
  Docker deploy. (Full PRD in the approved plan; backlog in `HANDOFF.md`.)

### Verified
- Backend compiles + packages to a runnable jar; frontend type-checks + builds.
- Booted against **real MySQL 9 + Redis 7** (installed via Homebrew); all 6 tables created;
  `/actuator/health` = UP.
- `scripts/e2e_test.py` — **8/8 checks pass**:
  admin login · non-UKM register → 403 · 60 OTP registrations · product+event creation ·
  scheduler warm+activate · **60 users race 20 units → exactly 20 ACCEPTED, 40 SOLD_OUT, 0
  oversell** · duplicate buy → ALREADY_BOUGHT · seckill orders persisted to MySQL via stream.

### Known limitations / deferred
- AI chatbot ON HOLD (stubbed; works without key). Real LLM API to be added later.
- Not yet a git repo (Codex to `git init` + push — see `HANDOFF.md` §10).
- Not yet deployed to a server.
- Backlog P1–P4 pending (admin event mgmt, order detail/pay, real email, reviews,
  detail pages, cart checkout, image upload, JUnit tests).

---

<!-- Codex: add the next version section ABOVE this line. Template:
## [vX.Y.Z] — YYYY-MM-DD — <short title>
### Added / Changed / Fixed / Verified
-->
