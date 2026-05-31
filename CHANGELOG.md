# Changelog

All notable changes to the FTSM E-Commerce Platform.
Format: date + semantic version, grouped into Added / Changed / Fixed / Verified.

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
