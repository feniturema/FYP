# FTSM E-Commerce — Implementation Handoff (for Codex)

## Traceability / authorship

| Scope | Authoring agent | Date | Trace |
|---|---|---|---|
| Foundation scaffold, core auth/SecKill/payment/admin-create/marketplace/chatbot stub, Docker, initial README/HANDOFF/CHANGELOG | Claude Code (Opus 4.8) | 2026-05-31 | `CHANGELOG.md` v0.1.0 |
| P1/P2 handoff completion: admin SecKill list/update/delete, order detail/pay-later, OTP resend, reviews, product/item details, tests/docs/git init | Codex (GPT-5) | 2026-05-31 | `CHANGELOG.md` v0.2.0; local git history |

This document is the single source of truth for continuing development. The **foundation
is built, compiles, and the critical high-concurrency path is verified end-to-end**. Codex
has completed the P1/P2 handoff scope listed in §6. Remaining future work is P3+ unless the
user reopens a completed item.

> **AI / Gemini chatbot is ON HOLD.** Do not work on it. It is stubbed and works without an
> API key (returns a placeholder). See §7. The user will supply a cheap LLM API later.

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
| Docker Compose + Nginx deploy | ✅ Done (not yet deployed to a server) |
| AI chatbot | ⏸️ ON HOLD (stubbed) |

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
- **External services (already installed & running via Homebrew on this machine):**
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
- **Hot path never touches MySQL.** Redis holds `seckill:stock:{eventId}` and
  `seckill:bought:{eventId}`. Orders are written asynchronously by the consumer.
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
POST /api/chat   (ON HOLD)
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

### P3.1 — Cart checkout
- `useCartStore` exists but is unused. Add a cart drawer/page that lists lines and checks out by
  creating an order per line (loop `orderApi.create`) or add a future batch endpoint. Keep it
  simple; C2C items are single-quantity.

### P3.2 — Image upload (optional)
- Today image is a URL field. Optionally add an upload endpoint storing to local disk
  (`/uploads`, served statically) or a cloud bucket; return the URL. Low priority.

### P4 — Tests
- **Backend (JUnit):** ✅ Done by Codex for `UkmEmailValidatorTest` (valid/invalid domains),
  `PaymentStrategyFactoryTest`, and a `@DataJpaTest` for a repository. Add a SecKill service
  test using an embedded/mocked Redis if feasible (or document the existing `e2e_test.py`).
- **Accept:** ✅ `JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn test` green on 2026-05-31.

---

## 7. AI chatbot — ON HOLD (context only, do not implement)

- `ChatService` calls Gemini via `WebClient`. With no `GEMINI_API_KEY` it returns a friendly
  placeholder, so the UI works. `ChatbotController` returns `Mono<ChatResponse>` (non-blocking).
- To enable later: set `GEMINI_API_KEY` (+ optional `GEMINI_MODEL`, `GEMINI_BASE_URL`). If a
  different provider/LLM is chosen, only `ChatService` needs changing (swap the request/response
  mapping and base URL); the controller and frontend `ChatWidget` stay the same.
- On macOS you may see a harmless netty DNS warning for outbound calls; add
  `io.netty:netty-resolver-dns-native-macos` (osx-aarch_64 classifier) if it bothers you.

---

## 8. Known gotchas (already handled — keep them in mind)

1. **JDK 25 breaks Lombok** — build with 17/21.
2. **MySQL reserved words** — `condition` → `item_condition`. Check new columns.
3. **Actuator mail health** — disabled in `application.yml` (`management.health.mail.enabled=false`)
   so a missing SMTP doesn't make `/health` report DOWN.
4. **SecKill timing** — a background `@Scheduled` (every 10s) warms Redis stock and flips event
   status. After creating an event, allow up to ~10s before it's buyable.
5. **Local MySQL root has no password** — run backend with `DB_PASSWORD=""` in dev.

---

## 9. Definition of done for the handoff scope (P1–P2)
- ✅ All P1 + P2 endpoints implemented per spec, following §4 conventions. Authored by Codex.
- ✅ Frontend pages wired and navigable; protected/admin routes enforced. Authored by Codex.
- ✅ README updated for SMTP OTP and reviews. Authored by Codex.
- ✅ AI left untouched (still works as placeholder). Original stub authored by Claude Code.
- Not rerun in this Codex pass: `python3 scripts/e2e_test.py` against live MySQL/Redis. Backend unit/JPA tests and frontend build are green; original 8/8 e2e verification remains recorded under Claude Code v0.1.0.

---

## 10. Version control

Codex initialized the local git repository on 2026-05-31 and created the first local commit:
`feat: complete p1 p2 handoff features`. No remote is configured yet.

```bash
cd /Users/fenituremas/Documents/FYP
git status
git log --oneline -1
# create a GitHub repo when ready, then:
# git remote add origin <repo-url>
# git branch -M main
# git push -u origin main
```
- `.gitignore` is already set at root / `backend/` / `frontend/` (excludes `.env`, `target/`,
  `node_modules/`, `dist/`). **Never commit `.env`** — only `.env.example`.
- Commit granularity: one logical feature per commit (e.g. `feat(reviews): add review API + UI`).
- **Append to `CHANGELOG.md`** a new version section for each meaningful batch of work, following
  the existing format (date + version + Added/Changed/Fixed/Verified). v0.1.0 is already logged.
