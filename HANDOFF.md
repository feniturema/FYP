# FTSM E-Commerce — Implementation Handoff

## Traceability / authorship

| Scope | Authoring agent | Date | Trace |
|---|---|---|---|
| Foundation scaffold, core auth/SecKill/payment/admin-create/marketplace/chatbot stub, Docker, initial README/HANDOFF/CHANGELOG | Claude Code (Opus 4.8) | 2026-05-31 | `CHANGELOG.md` v0.1.0 |
| P1/P2 handoff completion: admin SecKill list/update/delete, order detail/pay-later, OTP resend, reviews, product/item details, tests/docs/git init | Codex (GPT-5) | 2026-05-31 | `CHANGELOG.md` v0.2.0; git commit `29e0506` |
| v0.2.0 independent re-verification by Claude Code | Claude Code (Sonnet 4.6) | 2026-06-01 | `CHANGELOG.md` v0.2.0 "Verified by Claude Code" section |
| P3: cart checkout + image upload | Claude Code (Sonnet 4.6) | 2026-06-01 | `CHANGELOG.md` v0.3.0 |
| AI chatbot activated (DeepSeek OpenAI-compatible) | Claude Code (Sonnet 4.6) | 2026-06-01 | `CHANGELOG.md` v0.4.0–v0.4.2 |
| Docs aligned with code; compose `LLM_*` fix; upgrade plan | Claude Code | 2026-10-03 | `CHANGELOG.md` v0.4.3; `docs/UPGRADE_PLAN.md` |
| Upgrade implementation spec (v0.4.4), agent-executable revision (v0.4.5), per-phase execution packages (v0.4.6) | Claude Code | 2026-10-03 | `docs/CHANGE_SPEC.md`, `docs/phases/`, `docs/agent-prompts/` |
| Upgrade P0: baseline tag, Maven Wrapper, Flyway V1, k6 smoke tooling | Claude Code | 2026-10-04 | `CHANGELOG.md` v0.5.0; `docs/phases/P0.md` |
| Upgrade P1: Java 21, Spring Boot 3.5.16 (D1), virtual threads | Claude Code | 2026-10-04 | `CHANGELOG.md` v0.6.0; `docs/phases/P1.md` |
| Upgrade P2: transactional outbox + Kafka SecKill pipeline, conditional stock updates | Claude Code | 2026-10-04 | `CHANGELOG.md` v0.7.0; `docs/phases/P2.md` |
| Upgrade P3: SecKill sync comparison mode, A/B/C benchmark tooling (formal measurement pending) | Claude Code | 2026-10-05 | `CHANGELOG.md` v0.7.1; `docs/phases/P3.md` |
| Fix: SecKill event cache loads outside synchronized monitors (found while diagnosing H1 stalls) | Claude Code | 2026-10-05 | `CHANGELOG.md` [Unreleased]; `loadtest/results/H1-cache-fix/CACHE-FIX.md` |
| Upgrade P4a: catalog-core module split + root Maven Wrapper | Codex (review fixes: Claude Code) | 2026-10-06 | `CHANGELOG.md` v0.8.0; `docs/phases/P4a.md`; PR #7 |
| Upgrade P6a: Testcontainers integration tests + GitHub Actions CI | Claude Code | 2026-10-06 | `CHANGELOG.md` v0.9.0; `docs/phases/P6a.md` |
| Upgrade P4b: Spring AI assistant + MCP server + SSE | Claude Code (implementation) / Codex (handoff) | 2026-10-06 | `CHANGELOG.md` v0.10.0; `docs/phases/P4b.md`; branch `p4b-assistant-mcp` |

This document is the single source of truth for continuing development. The **foundation
is built, compiles, and the critical high-concurrency path is verified end-to-end**. Codex
has completed the P1/P2 handoff scope listed in §6. P3, P4a, P6a and P4b are implemented;
the assistant is now Spring AI/MCP based with authenticated SSE streaming. The remaining phases
(hybrid retrieval, multi-replica locking and optional Kubernetes work) are specified in
`docs/UPGRADE_PLAN.md` (rationale), `docs/CHANGE_SPEC.md` (master spec) and
`docs/phases/` + `docs/agent-prompts/` (one execution package and prompt per phase). Start P0 with
`docs/agent-prompts/START-P0.md`; known gaps in the current code are in §8 below.

---

## 1. Current status

| Area | State |
|---|---|
| Backend scaffold (Spring Boot 3.5.16 / Java 21 since P1; was 3.3.5 / 17) | ✅ Done, compiles, boots |
| Module layout (`catalog-core` + `backend`) | ✅ P4a (v0.8.0), merged via PR #7 (2026-10-05). Root reactor; runtime behavior unchanged |
| Frontend scaffold (Vite + React 18 + TS + Tailwind) | ✅ Done, builds |
| Auth: UKM-domain + OTP + JWT + roles | ✅ Done & verified |
| SecKill: Lua atomic deduct + Redis Stream consumer | ✅ Done & verified (no oversell) |
| Payment: mock Strategy pattern | ✅ Done |
| Admin: create product, create/list/update/delete seckill event | ✅ Done by Claude Code (create) + Codex (list/update/delete) |
| Marketplace (C2C + B2C browse/buy/detail/reviews) frontend | ✅ Done by Claude Code (browse/buy) + Codex (detail/reviews) |
| Order detail + pay-later frontend | ✅ Done by Codex |
| Reviews API/UI | ✅ Done by Codex |
| OTP resend endpoint/UI + SMTP docs | ✅ Done by Codex |
| Cart checkout (`/cart`, Zustand `useCartStore`, payment selector) | ✅ Done by Claude Code (v0.3.0) |
| Image upload (`POST /api/upload`, `ImageUpload` widget, static serving) | ✅ Done by Claude Code (v0.3.0) |
| Docker Compose + Nginx deploy | ✅ Done (not yet deployed to a server) |
| AI assistant (Spring AI + DeepSeek/MCP) | ✅ P4b implementation complete on `p4b-assistant-mcp`: authenticated SSE, five remote catalogue tools, local `my_orders`, bounded memory, Resilience4j fallbacks; no-key mode is deterministic |
| Automated tests | ✅ 107 Surefire tests (21 classes) + 16 Testcontainers `*IT` classes / 30 Failsafe cases (real MySQL 8.0.46 / Kafka 3.9.2 / Redis 8.10.2 and MCP server), all green in `./mvnw -B verify`; guarded by `scripts/ci/check_test_reports.py` and CI in `.github/workflows/ci.yml` |
| Baseline tag `v0.4.2-baseline` (annotated, peeled → `5f5fae4`) | ✅ P0 (v0.5.0) — on `origin` (pushed by the maintainer; `git ls-remote origin 'refs/tags/v0.4.2-baseline^{}'` → `5f5fae4…`) |
| Maven Wrapper `mvnw` (Maven 3.9.11, sha256-verified) | ✅ P0 (v0.5.0), moved to the repo root in P4a (v0.8.0) |
| Flyway schema migrations (`V1__baseline.sql`, `ddl-auto: validate`, legacy DBs baselined) | ✅ P0 (v0.5.0) — every entity change now needs a new migration (next: V2 in P2, see `docs/CHANGE_SPEC.md` §0.6); never edit a merged one |
| Load-test tooling (`loadtest/`, k6 2.3.0 via `scripts/tools/install_k6.sh`) | ✅ P0 (v0.5.0); P3 (v0.7.1) added `persist.sql`, `--drain none`, the three-throughput `summarize.py`, `reset.sh` and `loadtest/bench/` (configs A-baseline / B-sync / C-async in compose project `ftsm-p3-bench`) — smoke runs only so far |
| SecKill sync comparison mode (`SECKILL_MODE=sync`, `app.seckill.mode`; default `async`) | ✅ P3 (v0.7.1) — benchmark only: the order is written in the request thread, no outbox / Kafka; never make it the default. Smoke A3–A6 pass for A, B, C (`loadtest/results/_smoke/{A-baseline,B-sync,C-async}/`). **Formal measurement (H1) pending, by a person** (`docs/phases/P3.md` §7.3); README "Performance" stays `TBD` and no performance claim may be made until it is merged |
| Java 21 + Boot 3.5.16 (Hibernate 6.6.53, Flyway 11.7.2, Lombok 1.18.46, Connector/J 9.7.0, springdoc 2.8.17) | ✅ P1 (v0.6.0) — D1=boot-3.5.16; no V1_1 migration was needed (fresh and upgraded schemas identical) |
| Virtual threads (`VIRTUAL_THREADS`, default on) + `DB_POOL_SIZE` (default 20) | ✅ P1 (v0.6.0) — e2e and contention smoke pass with both settings; no pinned stacks observed (`scripts/db/evidence/p1/pinning.txt`). That check predates the P2 event cache, whose synchronous loader did pin carriers under load; fixed after P3 (§8 gotcha 7) |
| SecKill pipeline: MySQL outbox (`V2__seckill_outbox.sql`) → `OutboxRelay` → Kafka `seckill.orders` → `SeckillOrderListener`; 202 / 409 / 503; event-window cache; `SeckillReconciler` + `OutboxJanitor` | ✅ P2 (v0.7.0) — Redis Stream consumer removed; Redis 8.10.2 with AOF; keys hash-tagged `seckill:stock:{<id>}`. Acceptance A1–A14 pass (`scripts/p2/evidence/`). A6 was revised on 2026-10-04 (maintainer-approved) to per-request reconciliation with `scripts/p2/verify_crash.py`, because the old `orders == 202 received` contradicted §6.1; the old-spec failure is kept as `A6-oldspec-*` |
| Normal B2C / C2C checkout race | ✅ P2 (v0.7.0) — conditional `UPDATE` (`decrementStock`, `markSold`) inside `OrderService.create`'s transaction; A10: 20 concurrent buyers of a 1-unit product → exactly one order |
| Container image on Java 21 (`eclipse-temurin:21.0.12.1_1-jre-noble`, `JAVA_OPTS`) | ✅ P1 (v0.6.0) — container acceptance passed on local Docker Desktop: A9 image build on the pinned base, A10 health/API/Swagger, A11 in-container e2e 8/8; A12 cleanup complete. Evidence: `scripts/db/evidence/p1/container/` (commit `96a7d36`; `A11-backend.log` redactions in `redactions.txt`) |

### Verified by `scripts/e2e_test.py` (8/8 passing)
Admin login · non-UKM rejected (403) · 60 OTP registrations · product+event creation ·
scheduler warms+activates event · **60 users race 20 units → exactly 20 win, no oversell** ·
duplicate-buy → `ALREADY_BOUGHT` · orders persisted to MySQL via the outbox → Kafka pipeline
(step 7 polls up to 30 s). Since P2, rejections arrive as HTTP 409 with the same JSON body.

---

## 2. Environment & how to run

- **JDK: use 21** (since P1 the build targets `release 21`; other JDKs are not verified with Lombok 1.18.46).
  ```bash
  export JAVA_HOME=$(/usr/libexec/java_home -v 21)   # macOS
  ```
- **External services:** MySQL, Redis and (since P2) Kafka. Easiest: the compose services
  `docker compose up -d mysql redis kafka` (MySQL root/root on 3306, Redis 6379, Kafka 29092; run
  the backend with `KAFKA_BOOTSTRAP=127.0.0.1:29092`). The original author's macOS machine used
  Homebrew MySQL 9 (root without password → `DB_PASSWORD=""`) and Redis; Kafka still has to come
  from compose or another broker.
- **Backend (dev):**
  ```bash
  export JAVA_HOME=$(/usr/libexec/java_home -v 21)
  ./mvnw -B -pl backend -am -DskipTests package          # catalog-core + backend in one reactor, no install
  KAFKA_BOOTSTRAP=127.0.0.1:29092 java -jar backend/target/ecommerce-0.0.1-SNAPSHOT.jar   # db auto-created; Flyway migrates (V1, V2)
  # API http://localhost:8080 · Swagger http://localhost:8080/swagger-ui.html
  # Seeded admin: admin@ukm.edu.my / Admin@123
  # OTP codes are printed to the console (mail disabled by default)
  ```
  `./mvnw spring-boot:run` from the repo root does not work since P4a: the `ftsm-parent` aggregator has no
  main class, and `./mvnw -pl backend spring-boot:run` cannot resolve `catalog-core` unless it was installed.
  To use `spring-boot:run`, install first (and again after any `catalog-core` change):
  `./mvnw -B -pl backend -am -DskipTests install`, then `KAFKA_BOOTSTRAP=127.0.0.1:29092 ./mvnw -pl backend spring-boot:run`.
- **Frontend (dev):**
  ```bash
  cd frontend && npm install && npm run dev     # http://localhost:5173 (proxies /api)
  ```
- **Tests:** `./mvnw -B verify` (107 Surefire tests plus 30 Failsafe cases across 16 `*IT` classes;
  Testcontainers needs a running Docker), then
  `python3 scripts/ci/check_test_reports.py --expect scripts/ci/expected-tests.json`.
  The `h2` runtime profile was removed in P2 (H2 is test scope only).
- **Shared helpers for acceptance runs:** `scripts/lib/wait.sh` (`wait_http`, `wait_cmd` — every
  readiness wait must go through these), `scripts/db/lib.sh` (temporary MySQL 3307 / Redis 6380,
  `start_backend`; `compose:<project>:<env-file>[:<db>]` targets since P2), `loadtest/run.sh`
  (k6 smoke/benchmark runs; `--drain outbox --reject-status 409` for P2+ code, `--drain none` for
  `SECKILL_MODE=sync`), `loadtest/bench/` (P3 A/B/C benchmark: `infra_up.sh`, `run_config.sh`,
  `infra_down.sh`; see `loadtest/README.md` "Three configurations (P3)"),
  `scripts/p2/acceptance.sh` (P2 acceptance in compose project `ftsm-p2-acc`).
- **E2E test (backend must be running, with its log captured):**
  ```bash
  # if you started via jar: java -jar target/*.jar > /tmp/ftsm-real.log 2>&1 &
  # if via mvn: tee the output to a log and pass --log
  python3 scripts/e2e_test.py --users 60 --stock 20 --log /tmp/ftsm-real.log
  ```

---

## 3. Architecture (one screen)

```
React SPA ──/api──> Spring MVC controllers ──> services ──> JPA repos ──> MySQL
                                   │
SecKill buy ─► event window (Caffeine 5 s) ─► Lua deduct in Redis (T0) ─► INSERT order_outbox (T1, autocommit)
                                   │                                              │
                 202 + trackingToken / 409 / 503            OutboxRelay (100 ms): SKIP LOCKED batch ─► Kafka seckill.orders (T2)
                                   │                                              │
                    client polls /seckill/result          SeckillOrderListener ─► SeckillOrderWriter (T3: order + sold_count + FAKE_WALLET)
                                                                  │ failures: 3 retries ─► seckill.orders.DLT
                                                          SeckillReconciler (60 s) · OutboxJanitor (hourly)
```
- **The hot path writes exactly one MySQL row** (the outbox INSERT, outside any Spring transaction)
  and only answers 202 after it succeeded. Redis keys: `seckill:stock:{<eventId>}` and
  `seckill:bought:{<eventId>}` (hash-tagged). The listener is idempotent through
  `orders.tracking_token` and `uk_orders_buyer_seckill (buyer_id, seckill_event_id)`.
- `ACCEPTED` → 202; `SOLD_OUT` / `ALREADY_BOUGHT` / `NOT_ACTIVE` → **409**; `UNAVAILABLE` → **503**
  (outbox write not confirmed). Body is always `SeckillBuyResponse`.
- Every transaction boundary and failure case (what is retried, what may undersell, what is never
  compensated) is the table in `docs/phases/P2.md` §6.1 — read it before touching this path.
- OTP codes live in Redis (`otp:{email}`, 5-min TTL).

---

## 4. Conventions you MUST follow

**Backend** (`backend/src/main/java/my/edu/ukm/ftsm/ecommerce/`)
- Layering: `controller` → `service` (+ `service/impl`, `service/strategy`) → `repository` → `model`.
- DTOs are **Java records grouped per domain** in `dto/` (e.g. `ItemDtos.CreateItemRequest`,
  with a static `from(entity)` mapper on response records). Match this style.
- Entities use **Lombok** `@Getter/@Setter/@Builder/@NoArgsConstructor/@AllArgsConstructor`,
  enums nested in the entity, `@PrePersist` for `createdAt`.
- ⚠️ **Avoid SQL reserved words as column names.** `Item.condition` is mapped to
  `@Column(name="item_condition")`. Watch for others (`order`, `condition`, `rank`, `group`).
- Errors: throw `ResourceNotFoundException` (404), `BusinessException` (400),
  `UnauthorizedDomainException` (403). `GlobalExceptionHandler` already maps these — don't
  catch-and-return ad hoc error bodies.
- Auth in controllers: inject `@AuthenticationPrincipal AuthPrincipal principal`
  (`principal.userId()`, `.role()`). Never trust a userId from the request body.
- Security rules live in `config/SecurityConfig`. Public = `/api/auth/**`, `GET` on
  items/products/seckill-events, swagger, `/actuator/health`. `/api/admin/**` = `ROLE_ADMIN`.
  Everything else requires a JWT. **Add new admin endpoints under `/api/admin/**`.**
- Redis key names: centralized in `utils/RedisKeys`. Add new keys there.

**Frontend** (`frontend/src/`)
- Feature-first folders under `features/` (`auth`, `marketplace`, `seckill`, `chatbot`,
  `admin`); route-level wrappers in `pages/`; shared UI in `components/common` & `layout`.
- All HTTP through `services/api.ts` (typed modules: `authApi`, `itemApi`, `productApi`,
  `seckillApi`, `orderApi`, `adminApi`). The axios instance auto-attaches JWT & handles 401.
- State via Zustand: `useAuthStore` (token+user, persisted), `useCartStore`.
- Styling: Tailwind utility classes; brand color is `ukm` (`bg-ukm-700` etc., see
  `tailwind.config.js`). Reuse `Button`, `Input`, `Spinner`, `ProductCard`.
- Types live in `src/types/index.ts` — keep in sync with backend DTOs.

---

## 5. Existing API surface (do not duplicate)

```
POST /api/auth/register · /api/auth/verify-otp · /api/auth/resend-otp · /api/auth/login    GET /api/auth/me
GET  /api/items · /api/items/{id}    POST/PUT/DELETE /api/items[/{id}]   (auth; owner-scoped)
GET  /api/products · /api/products/{id}
GET  /api/seckill/events · /api/seckill/events/{id}
POST /api/seckill/{eventId}/buy  (202 + token | 409 SOLD_OUT/ALREADY_BOUGHT/NOT_ACTIVE | 503 UNAVAILABLE)
GET  /api/seckill/result?token=   (PENDING until the order is written)
GET  /api/orders · /api/orders/{id}   POST /api/orders   POST /api/orders/{id}/pay
GET/POST /api/reviews
POST /api/chat   (auth; DeepSeek assistant, live)
POST /api/upload (auth; multipart image)   GET /uploads/** (public)
POST /api/admin/products   PUT/DELETE /api/admin/products/{id}
GET/POST /api/admin/seckill-events   PUT/DELETE /api/admin/seckill-events/{id}
```

Data model: `User`, `Item`(C2C), `Product`(B2C), `SeckillEvent`, `Order`, `Review`.
`Review` now has DTO/service/controller/UI support, authored by Codex in v0.2.0.

---

## 6. Backlog — implement these (priority order)

Each item lists: files to create/edit, behavior, and acceptance criteria. Follow §4 conventions.
Write at least a happy-path test where noted.

### P1.1 — Admin: list / update / delete SecKill events — ✅ Done by Codex (v0.2.0)
- **Backend:** add to `SeckillService` + `AdminController`:
  - `GET /api/admin/seckill-events` → list all events (reuse `SeckillEventResponse`).
  - `PUT /api/admin/seckill-events/{id}` → update price/stock/times **only while status=PENDING**
    (throw `BusinessException` otherwise); reset `stockWarmed=false` so the scheduler re-warms.
  - `DELETE /api/admin/seckill-events/{id}` → delete event + clean Redis keys
    (`RedisKeys.seckillStock/seckillBought`).
- **Frontend:** in `features/admin/AdminDashboard.tsx`, add a table of events with edit/delete.
- **Accept:** admin can see/edit/remove events; editing an ACTIVE event is rejected.

### P1.2 — Order detail page + pay-later flow — ✅ Done by Codex (v0.2.0)
- **Frontend:** new `pages/OrderDetail.tsx` at route `/orders/:id` (protected). Show order,
  and for `PENDING` orders a "Pay" action calling `orderApi.pay(id, method)` with a method
  selector (`FAKE_WALLET` | `MOCK_FPX`). Link rows from `pages/Orders.tsx`.
- **Accept:** a PENDING order can be paid and flips to PAID/FAILED in the UI.

### P1.3 — Real email OTP delivery — ✅ Done by Codex (v0.2.0)
- No code change needed — it's wired. Set `MAIL_ENABLED=true` + SMTP envs (Gmail app password).
  See `EmailService`. Just **document and test** that a real email arrives, and add a
  "Resend code" button on `features/auth/OtpVerify.tsx` calling a new
  `POST /api/auth/resend-otp` (add a thin endpoint in `AuthController` → `authService.issueOtp`).
- **Accept:** resend works; real email received when enabled.

### P2.1 — Reviews feature (entity already exists) — ✅ Done by Codex (v0.2.0)
- **Backend:** new `dto/ReviewDtos` (records), `service/ReviewService`, `controller/ReviewController`:
  - `POST /api/reviews` (auth) — body: `targetType` (ITEM|PRODUCT|SELLER), `targetRefId`,
    `rating` 1–5, `comment`. Set `authorId` from `AuthPrincipal`. Validate rating range.
  - `GET /api/reviews?targetType=&targetRefId=` (public) — list + average rating.
  - Optional rule: only allow reviewing something the user has a PAID order for.
- **Frontend:** show reviews + average on a product/item detail view; add a review form for
  authenticated users. (Create `features/marketplace/ProductDetail.tsx` at `/product/:id` and
  `ItemDetail.tsx` at `/item/:id` if not present, and link cards to them.)
- **Accept:** can post and list reviews; average shown.

### P2.2 — Product/Item detail pages — ✅ Done by Codex (v0.2.0)
- Currently buying happens straight from cards. Add detail routes (`/product/:id`, `/item/:id`)
  with full description, image, reviews (P2.1), and buy button. Update `ProductCard` to link.

### P3.1 — Cart checkout — ✅ Done by Claude Code (v0.3.0)
- `pages/Cart.tsx` (`/cart`, protected); Navbar cart icon with badge; `onAddToCart` prop on
  `ProductCard`; sequential order creation per line; payment method selector; results page.

### P3.2 — Image upload — ✅ Done by Claude Code (v0.3.0)
- Backend `UploadController` + `WebMvcConfig` static serving; `ImageUpload` React component;
  wired into `SellItem` and `AdminDashboard`. Upload dir persisted via Docker volume.

### P4 — Tests
- **Backend (JUnit):** ✅ Done by Codex for `UkmEmailValidatorTest` (valid/invalid domains),
  `PaymentStrategyFactoryTest`, and a `@DataJpaTest` for a repository. Add a SecKill service
  test using an embedded/mocked Redis if feasible (or document the existing `e2e_test.py`).
- **Accept:** ✅ `JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn test` green on 2026-05-31.

---

## 7. AI assistant — P4b implementation complete

P4b replaces the blocking WebClient chatbot with Spring AI `ChatClient` and a small MCP tool
plane. `POST /api/assistant/stream` accepts a JSON message and optional conversation id as an authenticated SSE endpoint; the old
`POST /api/chat` endpoint remains as an aggregated compatibility path.

- `LLM_PROVIDER=deepseek` selects the DeepSeek OpenAI-compatible chat model; `LLM_PROVIDER=none`
  or a blank `LLM_API_KEY` keeps startup deterministic and avoids external calls.
- The MCP server exposes five catalogue tools (`search_products`, `search_secondhand_items`,
  `get_product_detail`, `get_stock`, `list_flash_sales`). The backend adds local `my_orders`
  with the authenticated user id from `ToolContext`.
- Resilience4j supplies a circuit breaker and rate limiter; Reactor applies a 30-second stream timeout. In-memory conversation
  memory and tool-call records are bounded (1,000 conversations, two-hour idle expiry).
- Security context is saved for async dispatch so JWT identity survives the SSE lifecycle.
- P4b local acceptance A4–A10 passed in `/tmp/ftsm-p4b/evidence-run1/acceptance-summary.txt`.
  The real-provider evaluation cases E1–E2 and optional Inspector screenshot E3 remain pending.

---

## 8. Known gotchas (already handled — keep them in mind)

1. **Build with JDK 21.** The build targets `release 21` since P1; JDK 25 broke the old Lombok
   1.18.36 (`TypeTag :: UNKNOWN`) and has not been re-verified with the Boot-managed 1.18.46.
2. **MySQL reserved words** — `condition` → `item_condition`. Check new columns.
3. **Actuator mail health** — disabled in `application.yml` (`management.health.mail.enabled=false`)
   so a missing SMTP doesn't make `/health` report DOWN.
4. **SecKill timing** — a background `@Scheduled` (every 10s) warms Redis stock (`SET NX`) and
   flips event status. After creating an event, allow up to ~10s before it's buyable. Orders
   appear asynchronously (outbox → Kafka), normally well under a second.
5. **Local MySQL root has no password** (Homebrew setup) — run backend with `DB_PASSWORD=""` in dev.
6. **`SeckillEvent` uses `@DynamicUpdate`** so status/flag saves never overwrite `sold_count`,
   which the listener changes with a conditional `UPDATE`. Keep it, or never save stale events.
7. **No blocking work inside `synchronized` or `ConcurrentHashMap.compute` on virtual threads (JDK 21).**
   A synchronous Caffeine loader runs inside `ConcurrentHashMap.compute`'s monitor; with a DB query
   in it, the loader pins its carrier and every same-key caller blocks on the monitor and pins one
   too, so with as many callers as carriers (8 on the dev Mac) no virtual thread in the JVM runs.
   Reproduced deterministically; `SeckillEventCache` now uses `AsyncCache` with its own
   virtual-thread loader (`loadtest/results/H1-cache-fix/CACHE-FIX.md`). Use the same pattern for
   any new cache that loads from MySQL, Redis or HTTP.

### Known gaps (NOT handled yet — see `docs/UPGRADE_PLAN.md` §1 for the fix plan)

8. **Single replica only.** The scheduled tasks (`reconcileEvents`, `OutboxRelay`,
   `SeckillReconciler`, `OutboxJanitor`) run on every instance; warm-up is `SET NX` now, but the
   relay/reconciler are not designed for several instances until ShedLock (P6b).
9. **SecKill can undersell in four rare windows** (crash between Redis and the outbox INSERT,
   unconfirmable INSERT, failed compensation, consumer backlog not drained within the grace).
   The reconciler reports them (`seckill.reconcile.*` counters, WARN logs); nothing is repaired
   automatically. Oversell is prevented twice (Lua, `sold_count < seckill_stock`).
10. **DLT publish failure blocks the partition** until the DLT is reachable: the record is not committed and
   is redelivered (proven by P6a `DltPublishFailureIT`, which runs its broker with
   `auto.create.topics.enable=false`). With the broker default (true, also in `docker-compose.yml`) a deleted
   DLT is silently recreated with broker defaults (1 partition) on the next publish.
11. **Uploads on local disk** (`UPLOAD_DIR` / `uploads_data` volume) — not shared across replicas.
12. **`prod` profile** is set by Docker Compose but has no overrides in `application.yml`.
(The P0-era gaps "normal checkout race" and "Redis Stream durability" were closed in P2.)

---

## 9. Definition of done — cumulative (P1–P4 / v0.4.0)
- ✅ P1/P2: admin SecKill CRUD, order detail/pay, resend-OTP, reviews, detail pages, JUnit tests. (Codex v0.2.0)
- ✅ P2 independently re-verified by Claude Code (Sonnet 4.6) on 2026-06-01: 8/8 e2e, all new endpoints smoke-tested.
- ✅ P3.1 cart checkout: `pages/Cart.tsx`, Navbar badge, `ProductCard.onAddToCart`, sequential checkout, payment selector. (Claude Code v0.3.0)
- ✅ P3.2 image upload: `UploadController`, `WebMvcConfig`, `ImageUpload` component, wired in SellItem + Admin, Docker volume. (Claude Code v0.3.0)
- ✅ AI assistant P4b: Spring AI + DeepSeek/MCP, authenticated SSE and compatibility POST endpoint; `ChatWidget` uses SSE. (v0.10.0)
- ✅ All builds green (backend + frontend) and `scripts/e2e_test.py` 8/8 after v0.4.0.
- ⬜ Remaining: real server deployment, HTTPS, real SMTP verification end-to-end.
- ⬜ Next: v0.5+ upgrade. Master spec: `docs/CHANGE_SPEC.md` (dependencies, registries, coverage matrix).
  Per-phase execution packages: `docs/phases/<phase>.md`; per-phase agent prompts: `docs/agent-prompts/<phase>.md`.
  Order: P0 → P1 → P2 → P3 → P4a → P6a → P4b → P5a → (human labelling) → P5b → P6b → P7 (optional).
  P0 (v0.5.0), P1 (v0.6.0), P2 (v0.7.0) and P3 (v0.7.1: sync comparison mode, benchmark tooling, smoke)
  are implemented and merged (P3 via PR #5; the event-cache pinning fix, CHANGELOG [Unreleased], via PR #6,
  both 2026-10-05) and P4a (v0.8.0, PR #7, 2026-10-05). P6a (v0.9.0: Testcontainers ITs + CI) was
  merged via PR #8. P4b is implemented on `p4b-assistant-mcp`; local A4–A10 acceptance and the full
  Maven verification are green. A draft PR is being prepared for review. The next implementation phase
  after P4b is P5a (hybrid retrieval); P5b remains gated on human labelling.
  An H1 attempt on the dev MacBook (2026-10-05) is incomplete and diagnostic only: no config had a valid
  step at RATE ≥ 1000, and its numbers are not performance results (evidence kept locally, uncommitted).
  P3 H1 (formal A/B/C measurement on a
  dedicated machine, separate PR `P3-results: formal benchmark (A/B/C)`) is a pending human task and
  can run in parallel with later phases.
  Production cutover to v0.7.0 with existing data is a manual step (README "Upgrading … to v0.7.0",
  `scripts/p2/precheck_cutover.py`, `docs/phases/P2.md` §10.1).
  D1 decided 2026-10-04: `D1=boot-3.5.16` (stay on Boot 3.5.16; no further OSS patches on the 3.5 line).
  Human decisions pending: D2 (embedding provider, gates P5b), D3 (implement P7).

---

## 10. Version control

Codex initialized the local git repository and pushed to GitHub on 2026-05-31.
Remote: `origin → https://github.com/feniturema/FYP.git`, branch `main`.

```bash
cd /Users/fenituremas/Documents/FYP
git status         # should be clean after each commit
git log --oneline  # review history
git push           # push committed work to origin/main
```
- `.gitignore` is already set at root / `backend/` / `frontend/` (excludes `.env`, `target/`,
  `node_modules/`, `dist/`). **Never commit `.env`** — only `.env.example`.
- Commit granularity: one logical feature per commit (e.g. `feat(reviews): add review API + UI`).
- **Append to `CHANGELOG.md`** a new version section for each meaningful batch of work, following
  the existing format (date + version + Added/Changed/Fixed/Verified). v0.1.0 is already logged.
