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

This document is the single source of truth for continuing development. The **foundation
is built, compiles, and the critical high-concurrency path is verified end-to-end**. Codex
has completed the P1/P2 handoff scope listed in §6. P3 and AI chatbot are also complete.
The next phase (v0.5+: Java 21, Outbox + Kafka, Spring AI/MCP, hybrid retrieval, CI/K8s)
is specified in `docs/UPGRADE_PLAN.md` (rationale) and `docs/CHANGE_SPEC.md` (file-by-file
implementation spec, one PR per phase); known gaps in the current code are in §8 below.

---

## 1. Current status

| Area | State |
|---|---|
| Backend scaffold (Spring Boot 3.3 / Java 17) | ✅ Done, compiles, boots |
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
| AI chatbot (DeepSeek, OpenAI-compat, product-context aware) | ✅ ACTIVE — `LLM_API_KEY` wired (v0.4.0); Docker Compose passes `LLM_*` since v0.4.3 |
| Automated tests | ⚠️ 5 JUnit tests (3 classes) + `scripts/e2e_test.py` (8 checks); no SecKill service/integration test yet |

### Verified by `scripts/e2e_test.py` (8/8 passing)
Admin login · non-UKM rejected (403) · 60 OTP registrations · product+event creation ·
scheduler warms+activates event · **60 users race 20 units → exactly 20 win, no oversell** ·
duplicate-buy → `ALREADY_BOUGHT` · orders persisted to MySQL via stream consumer.

---

## 2. Environment & how to run

- **JDK: use 17 or 21. NEVER build with JDK 25** (Lombok breaks: `TypeTag :: UNKNOWN`).
  ```bash
  export JAVA_HOME=$(/usr/libexec/java_home -v 21)   # macOS
  ```
- **External services (the original author's macOS dev machine used Homebrew):**
  - Redis 7 — `redis-cli ping` → `PONG`  (start: `brew services start redis`)
  - MySQL 9 — `mysqladmin ping`  (start: `brew services start mysql`); **root has no local password**
- **Backend (dev):**
  ```bash
  cd backend
  export JAVA_HOME=$(/usr/libexec/java_home -v 21)
  DB_PASSWORD="" mvn spring-boot:run          # MySQL on localhost, db auto-created
  # API http://localhost:8080 · Swagger http://localhost:8080/swagger-ui.html
  # Seeded admin: admin@ukm.edu.my / Admin@123
  # OTP codes are printed to the console (mail disabled by default)
  ```
- **Frontend (dev):**
  ```bash
  cd frontend && npm install && npm run dev     # http://localhost:5173 (proxies /api)
  ```
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
SecKill buy ─► Lua (atomic deduct in Redis) ─► XADD seckill:orders (Redis Stream)
                                   │                                  │
                          202 + trackingToken              SeckillStreamConsumer (poll)
                                   │                                  │
                    client polls /seckill/result         persists Order + mock payment
```
- **Hot path never writes to MySQL.** It does one `findById` on `seckill_events` to check the
  time window, then everything else is Redis: `seckill:stock:<eventId>` and
  `seckill:bought:<eventId>` (plain ids, no `{}` hash tag). Orders are written
  asynchronously by the consumer, which also settles payment with `FAKE_WALLET`.
- Sold out / already bought / not active return **HTTP 200** with a `result` code; only
  `ACCEPTED` returns 202.
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
POST /api/seckill/{eventId}/buy  (202 + token)    GET /api/seckill/result?token=
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

## 7. AI chatbot — ACTIVE

**AI chatbot is now ACTIVE** (v0.4.0). It uses the **DeepSeek** OpenAI-compatible API.

- Provider: DeepSeek (`https://api.deepseek.com/v1`), default model `deepseek-v4-flash`
  (`deepseek-v4-pro` also works; it is a reasoning model, hence `max_tokens: 2048`).
- API key stored in `.env` as `LLM_API_KEY` (gitignored — never commit it).
- `ChatService` builds a product-context prompt (products whose name/category appears in the
  message, max 10; the whole catalogue when it has ≤ 10 products — there is no
  tool/function calling and no product `active` flag)
  and calls `POST /chat/completions` via WebClient. Falls back to a friendly placeholder if
  `LLM_API_KEY` is blank.
- `ChatbotController` returns `ChatResponse` (blocks on the Mono) — required to make Spring
  Security 6 `@EnableMethodSecurity` + servlet `SecurityContextHolder` play nicely together.
- `ChatWidget.tsx` is unchanged; the floating 💬 button on every page is now live.
- To switch provider: set `LLM_BASE_URL` + `LLM_MODEL` in `.env`; any OpenAI-compatible
  endpoint works (OpenAI, Groq, Together, local Ollama, etc.).
- On macOS you may see a harmless netty DNS warning; add
  `io.netty:netty-resolver-dns-native-macos` (osx-aarch_64 classifier) to `pom.xml` if it bothers you.

---

## 8. Known gotchas (already handled — keep them in mind)

1. **JDK 25 breaks Lombok** — build with 17/21.
2. **MySQL reserved words** — `condition` → `item_condition`. Check new columns.
3. **Actuator mail health** — disabled in `application.yml` (`management.health.mail.enabled=false`)
   so a missing SMTP doesn't make `/health` report DOWN.
4. **SecKill timing** — a background `@Scheduled` (every 10s) warms Redis stock and flips event
   status. After creating an event, allow up to ~10s before it's buyable.
5. **Local MySQL root has no password** — run backend with `DB_PASSWORD=""` in dev.

### Known gaps (NOT handled yet — see `docs/UPGRADE_PLAN.md` §1 for the fix plan)

6. **Single replica only.** `SeckillService.reconcileEvents()` runs on every instance; two
   replicas could both see `stockWarmed=false` and re-`SET` Redis stock after sales began.
7. **Normal checkout race.** `OrderService.buildProductOrder` / `buildItemOrder` do
   read-modify-write without a row lock or conditional `UPDATE`, so concurrent buyers can
   oversell a B2C product or double-sell a C2C item. (SecKill is not affected.)
8. **Redis Stream durability.** Lua deduction and `XADD` are separate calls; Redis runs with
   default RDB snapshots only. A crash between them, or a Redis restart, can lose accepted
   orders (results in under-selling, never oversell).
9. **No schema migrations.** Hibernate `ddl-auto: update`; no Flyway/Liquibase.
10. **Uploads on local disk** (`UPLOAD_DIR` / `uploads_data` volume) — not shared across replicas.
11. **`prod` profile** is set by Docker Compose but has no overrides in `application.yml`.

---

## 9. Definition of done — cumulative (P1–P4 / v0.4.0)
- ✅ P1/P2: admin SecKill CRUD, order detail/pay, resend-OTP, reviews, detail pages, JUnit tests. (Codex v0.2.0)
- ✅ P2 independently re-verified by Claude Code (Sonnet 4.6) on 2026-06-01: 8/8 e2e, all new endpoints smoke-tested.
- ✅ P3.1 cart checkout: `pages/Cart.tsx`, Navbar badge, `ProductCard.onAddToCart`, sequential checkout, payment selector. (Claude Code v0.3.0)
- ✅ P3.2 image upload: `UploadController`, `WebMvcConfig`, `ImageUpload` component, wired in SellItem + Admin, Docker volume. (Claude Code v0.3.0)
- ✅ AI chatbot ACTIVE: DeepSeek OpenAI-compatible integration; `ChatService` product-context aware; `ChatWidget` live. (Claude Code v0.4.0)
- ✅ All builds green (backend + frontend) and `scripts/e2e_test.py` 8/8 after v0.4.0.
- ⬜ Remaining: real server deployment, HTTPS, real SMTP verification end-to-end.
- ⬜ Next: v0.5+ upgrade per `docs/UPGRADE_PLAN.md` (start with its P0: baseline tag + k6 baseline).

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
