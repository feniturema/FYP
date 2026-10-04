# Changelog

All notable changes to the FTSM E-Commerce Platform.
Format: date + semantic version, grouped into Added / Changed / Fixed / Verified.

---

## [Unreleased] — 2026-10-04 — Specification quality corrections

### Fixed
- P0 `wait_cmd` now validates its invocation and bounds each retry with coreutils `timeout`, so a blocked readiness command cannot exceed the declared deadline.
- P4b now defines a deterministic seeded test administrator and `get_test_token.py` flow for all authenticated acceptance requests.
- P4b `McpServerIT` now has an explicit test-only Flyway migration sequence and container property wiring while production mcp-server keeps Flyway disabled.
- The master spec and agent prompts now make the document-branch merge a hard P0 gate.
- Added `docs/agent-prompts/START-P0.md`, a final kickoff prompt that delegates only P0 and requires evidence-based draft PR completion.
- P1 and P6b now require fixed tool/image/schema versions; unavailable pinned artifacts stop the phase instead of silently falling back to newer versions.

### Not executed
- These are documentation-only corrections. No application build or integration acceptance was run.

---

## [v0.4.6] — 2026-10-03 — Per-phase execution packages

Authored by Claude Code. Docs only (`docs/`, `README.md`, `HANDOFF.md`, `CHANGELOG.md`); no
application code, configuration or Compose changes.

### Added
- `docs/phases/{P0,P1,P2,P3,P4a,P6a,P4b,P5a,P5b,P6b,P7}.md`: one complete execution package per phase.
  Each has the same 12 sections: goals and non-goals, preconditions, inputs and outputs, file list,
  ordered tasks, contracts, configuration and run commands, tests, acceptance matrix, upgrade and
  recovery, PR gate, and prompt.
- `docs/agent-prompts/*.md`: a standalone prompt for every phase.

### Changed
- `docs/CHANGE_SPEC.md` rewritten as the master spec:
  - verified repo facts and evidence markers;
  - phase order — P6a moves before P4b so that later phases can add integration tests;
  - version baseline with evidence;
  - migration, environment variable, port and cross-phase symbol registries;
  - stop rules, human decisions (D1/D2/D3/L1/B1) and known risks;
  - coverage matrix, issues fixed, unverified checklist, and readiness per phase.
- `docs/UPGRADE_PLAN.md`: aligned with this round's experiment results — MCP client starter,
  no-key startup, async security, vector store module, uploads, Boot 3.5 support status (D1) and
  the new phase order.

### Verified — 2026-10-03, in scratch projects outside the repo
- Boot 3.5.16 + Spring AI 1.1.8:
  - with `spring.ai.model.chat=none` alone, startup still fails (ChatClient auto-configuration);
  - a deepseek provider with an empty key fails startup;
  - an EnvironmentPostProcessor guard works;
  - the MCP client starter fails startup when the server is unreachable;
  - the MCP streamable server endpoint is `/mcp`, and the Java client can list and call tools;
  - `prompt().system()` replaces `defaultSystem`;
  - a `Flux`/`Mono` endpoint passes authentication on real Tomcat without saving the
    SecurityContext, and MockMvc `asyncDispatch` is not a faithful test of this.
- Compiled against the APIs the spec relies on, including `SyncMcpToolCallbackProvider`,
  `ToolCallbacks`, `RedisVectorStore.builder`, `FilterExpressionBuilder`,
  `W3CTraceContextPropagator`, and the MCP transport `connectTimeout`.
- k6 2.3.0: `dropped_iterations` thresholds, result-tagged counters, checksum values.
- Version, tag, checksum and digest values are listed in `docs/CHANGE_SPEC.md` §0.5.

### Not executed
- No phase was implemented. All integration acceptance listed in `docs/CHANGE_SPEC.md` §3 is pending.

---

## [v0.4.5] — 2026-10-03 — Upgrade spec made agent-executable

Authored by Claude Code. Docs only (`docs/`, `README.md`, `HANDOFF.md`, `CHANGELOG.md`).

### Changed
- `docs/CHANGE_SPEC.md`:
  - §0 now records the verified repo facts: `main` = `5f5fae4` = v0.4.2; v0.4.3–v0.4.5 live only
    on the docs branch; no tags; no Maven Wrapper.
  - Fixed the baseline naming: tag `v0.4.2-baseline` → `5f5fae4`. Earlier docs wrongly called it
    v0.4.0.
  - P0 rewritten as a self-contained execution package: preconditions, Docker-less fallback,
    Wrapper with sha256, reproducible schema export, fresh/legacy schema-equivalence check,
    k6 scripts, smoke vs formal runs, file list, a 17-item acceptance matrix, stop rules, and a
    ready-to-use prompt.
  - Phase dependencies fixed: P6a now depends on P2 and P4a; P5b is gated on human labelling and
    the embedding decision; P7 is optional.
  - Maven working directory defined per phase (`cd backend && ./mvnw` until P4a).
  - All versions pinned or attributed to a BOM, including Boot 3.5.16, Spring AI 1.1.8,
    ShedLock 6.10.0, OTel agent 2.32.0 (sha256), otel-lgtm 0.35.0, k6 2.3.0 and Testcontainers
    1.21.4.
  - Later phases clarified:
    - P2: `SECKILL_PAYMENT=FAKE_WALLET`; when the reconciler sets `reconciled`.
    - P4b: no-key startup, MCP-down startup, SecurityContext saved for ASYNC dispatch (no broad
      `permitAll`) with tests, read-only DB user script without SQL injection, tool recorder
      covers local tools.
    - P5a: seller mapping and idempotent import.
    - P5b: no-embedding startup test.
    - P6a: correct Redis/Kafka Testcontainers classes.
  - k6 steady-state QPS is now computed as `count / steady seconds` (k6's exported sub-metric
    rate is averaged over the whole test, verified on k6 2.3.0).
- `docs/UPGRADE_PLAN.md`: same version and baseline fixes; phase table and §12 now defer to
  `CHANGE_SPEC.md` §0.
- `README.md`, `HANDOFF.md`: traceability rows for v0.4.3–v0.4.5; baseline commit/tag; next step.
- `CHANGELOG.md`: the v0.4.3 entry now states that it included a compose change.

### Verified — 2026-10-03
- `mvn -B verify` on `5f5fae4` with JDK 21.0.11 / Maven 3.9.11: 5 tests, 0 failures.
- Maven Wrapper generation command (plugin 3.3.4, Maven 3.9.11 + sha256) works (tried in a
  scratch directory, not committed).
- k6 2.3.0: `k6/crypto` HS256 signing matches Python; `count==N` thresholds exit 99 on
  mismatch; `handleSummary` output format.

### Not executed
- No P0 step was run against MySQL (no Docker daemon in the authoring environment). Schema export,
  Flyway, e2e and k6 runs against the backend are all left to P0.

---

## [v0.4.4] — 2026-10-03 — Implementation spec for the upgrade

Authored by Claude Code. Docs only.

### Added
- `docs/CHANGE_SPEC.md`: file-by-file implementation spec for the v0.5+ upgrade, split into
  agent-sized phases (P0–P7, one PR each) with migrations, code skeletons, config, tests,
  acceptance commands and out-of-scope lists.

### Changed
- `docs/UPGRADE_PLAN.md`: time estimates replaced by phase dependencies and an agent
  execution guide; links to the spec; Flyway dependency note corrected for Boot 3.x.
- `README.md`, `HANDOFF.md`: point to the spec.

---

## [v0.4.3] — 2026-10-03 — Docs aligned with code + upgrade plan

Authored by Claude Code. No application (Java/TypeScript) logic changed, but this is **not
docs-only**: it includes a deployment-config fix in `docker-compose.yml`.

### Fixed
- `docker-compose.yml`: backend now receives `LLM_API_KEY` / `LLM_BASE_URL` / `LLM_MODEL`
  (it was still passing the obsolete `GEMINI_API_KEY` / `GEMINI_MODEL`, so the assistant
  always answered "not configured" under Docker).

### Changed — Docs
- `README.md`: DeepSeek instead of Gemini; traceability rows for v0.3.0–v0.4.3; SecKill
  description corrected (hot path reads the event row once and never *writes* MySQL;
  non-accepted results return 200); added cart, upload and assistant flows; accurate test
  inventory (5 JUnit tests + e2e script); new "Known limitations" section; full env-var list.
- `HANDOFF.md`: chatbot no longer marked ON HOLD; default model `deepseek-v4-flash`;
  `/api/upload` added to the API surface; architecture note corrected; new
  "Known gaps" list (single-replica scheduler, normal-checkout race, Stream durability,
  no migrations, local uploads, empty `prod` profile).
- `backend/pom.xml`: stale "WebClient for Gemini" comment.

### Added
- `docs/UPGRADE_PLAN.md`: gap analysis of the FTSM upgrade spec against this codebase and a
  phased plan (P0–P7) for Java 21, Outbox + Kafka, k6, Spring AI/MCP, hybrid retrieval,
  Testcontainers/CI/K8s/OpenTelemetry.

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
