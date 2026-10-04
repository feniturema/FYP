# FTSM E-Commerce Platform

A high-concurrency campus e-commerce platform for the FTSM / UKM community.

## Traceability

| Version / scope | Authoring agent | Date | Notes |
|---|---|---|---|
| v0.1.0 foundation scaffold + verified SecKill core | Claude Code (Opus 4.8) | 2026-05-31 | Original project structure, core backend/frontend, e2e SecKill verification, and initial docs. |
| v0.2.0 P1/P2 feature completion | Codex (GPT-5) | 2026-05-31 | Admin SecKill management, order detail/pay-later, OTP resend docs/UI/API, reviews, detail pages, tests, changelog updates, and git initialization. |
| v0.3.0 P3 cart checkout + image upload | Claude Code (Sonnet 4.6) | 2026-06-01 | `/cart`, `POST /api/upload`, `ImageUpload` widget. |
| v0.4.0–v0.4.2 AI chatbot live (DeepSeek) | Claude Code (Sonnet 4.6) | 2026-06-01 | OpenAI-compatible client, default model `deepseek-v4-flash`. |
| v0.4.3 docs aligned with code + compose `LLM_*` fix + upgrade plan | Claude Code | 2026-10-03 | Docs **and** a deployment-config fix (`docker-compose.yml`); `docs/UPGRADE_PLAN.md` added. |
| v0.4.4 implementation spec | Claude Code | 2026-10-03 | `docs/CHANGE_SPEC.md` added (docs only). |
| v0.4.5 spec made agent-executable | Claude Code | 2026-10-03 | Repo facts verified, P0 execution package, pinned versions (docs only). |
| v0.4.6 per-phase execution packages | Claude Code | 2026-10-03 | `docs/phases/` + `docs/agent-prompts/` for all 11 phases, coverage matrix, registries (docs only). |
| v0.5.0 P0: baseline tag, Maven Wrapper, Flyway V1, k6 smoke | Claude Code | 2026-10-04 | `backend/mvnw`, `V1__baseline.sql`, `scripts/lib`, `scripts/db`, `loadtest/`; no application code changed. |

The application code is unchanged since v0.4.2 (commit `5f5fae4`), which is the pre-upgrade
baseline (annotated tag `v0.4.2-baseline`, introduced by upgrade phase P0; see `CHANGELOG.md`
v0.5.0 for its push status). P0 (v0.5.0) added the
Maven Wrapper, Flyway-managed schema and the k6 load-test tooling without changing application
code. See `CHANGELOG.md` for per-version details, `HANDOFF.md` for the implementation
handoff/status ledger, `docs/UPGRADE_PLAN.md` for the planned v0.5+ upgrade
(Java 21, Outbox + Kafka, Spring AI/MCP, hybrid retrieval, K8s) and
`docs/CHANGE_SPEC.md` for the master implementation spec, with one execution package per phase in
`docs/phases/` and a ready-to-use agent prompt per phase in `docs/agent-prompts/`.
Use [`docs/agent-prompts/START-P0.md`](docs/agent-prompts/START-P0.md) to start the staged implementation; it delegates P0 only and requires the documented evidence and draft-PR gate.

- **Hybrid marketplace** — students list second-hand items (C2C) and an official
  admin store sells products (B2C) with flash-sale **SecKill** events.
- **Redis + Lua SecKill engine** — atomic stock deduction + one-per-user guard in Redis;
  orders are persisted **asynchronously** via a **Redis Stream** consumer, so the buy
  request never writes to MySQL (it does one read of the event row to check the time window).
- **AI shopping assistant** — DeepSeek via an OpenAI-compatible `/chat/completions` API,
  called through WebClient. Relevant products are injected into the prompt by simple
  keyword matching (no tool / function calling yet).
- **UKM-only auth** — `@ukm.edu.my` / `@siswa.ukm.edu.my` + **email OTP** + JWT.

## Tech stack

| Layer | Tech |
|---|---|
| Frontend | Vite 5, React 18, TypeScript, Tailwind CSS, Zustand, React Router 6, Axios |
| Backend | Java 17, Spring Boot 3.3.5, Spring Data JPA (Hibernate `ddl-auto: validate`), Flyway migrations, Spring Security + JWT (jjwt), Spring Mail, WebClient, springdoc-openapi |
| Data | MySQL 8, Redis 7 (Lua + Streams + OTP TTL keys) |
| AI | DeepSeek (OpenAI-compatible; default `deepseek-v4-flash`) — any OpenAI-compatible provider via `LLM_BASE_URL` |
| Infra | Docker Compose + Nginx reverse proxy (frontend container) |

---

## Prerequisites

- **JDK 17 or 21** (the build targets Java 17). ⚠️ **Do not build with JDK 25** —
  the pinned Lombok (1.18.36) is not compatible and the build fails with
  `TypeTag :: UNKNOWN`. If `cd backend && ./mvnw -v` reports JDK 25, point Maven at JDK 21:
  ```bash
  export JAVA_HOME=$(/usr/libexec/java_home -v 21)   # macOS
  ```
- No Maven install needed: use the Maven Wrapper `backend/mvnw` (Maven 3.9.11, download verified
  by `distributionSha256Sum`).
- **Node 20+** and npm.
- For local dev: a running **MySQL 8.0** and **Redis 7** (or use Docker, below).

---

## Quick start — local development

### 1. Backend
```bash
cd backend
export JAVA_HOME=$(/usr/libexec/java_home -v 21)   # if needed
# Needs MySQL + Redis reachable on localhost (defaults: root/root, db auto-created).
./mvnw spring-boot:run
```
- API: http://localhost:8080
- Swagger UI: http://localhost:8080/swagger-ui.html
- Seeded admin: `admin@ukm.edu.my` / `Admin@123` (plus 3 sample B2C products)
- **OTP codes** are printed to the backend console (mail is disabled by default).
- To enable the assistant, export `LLM_API_KEY` (and optionally `LLM_MODEL`, `LLM_BASE_URL`).

> No MySQL handy? Run with the in-memory H2 profile (still needs Redis):
> ```bash
> cd backend && SPRING_PROFILES_ACTIVE=h2 ./mvnw spring-boot:run
> ```

### 2. Frontend
```bash
cd frontend
npm install
npm run dev          # http://localhost:5173  (proxies /api -> :8080)
```

---

## Run everything with Docker (single server / VPS)

```bash
cp .env.example .env     # edit secrets (JWT_SECRET, DB_PASSWORD, LLM_API_KEY, SMTP…)
docker compose up -d --build
```
- Public site: `http://<server-ip>/`  (Nginx serves the SPA and proxies `/api`, `/uploads`, Swagger)
- Backend is also exposed directly on `:8080`; MySQL `:3306` and Redis `:6379` are
  published too — firewall them on a public server. Host ports can be overridden with
  `MYSQL_HOST_PORT`, `REDIS_HOST_PORT`, `BACKEND_HOST_PORT` and `FRONTEND_HOST_PORT`
  (defaults 3306 / 6379 / 8080 / 80). Images are pinned (`mysql:8.0.46`, `redis:7.4.6-alpine`).
- Uploaded images persist in the `uploads_data` volume.
- For HTTPS on a real domain, terminate TLS at an outer Nginx/Caddy or add
  Certbot/Let's Encrypt in front of the `frontend` container.

---

## Database migrations

The schema is owned by **Flyway** (`backend/src/main/resources/db/migration/`); Hibernate runs
with `ddl-auto: validate` and refuses to start if entities and tables disagree.

- `V1__baseline.sql` is the v0.4.2 schema exported from a real MySQL 8.0 by
  `scripts/db/export_baseline_schema.sh`. **Never edit a merged migration**; every entity change
  needs a new `V<n>__*.sql` (numbers are pre-allocated in `docs/CHANGE_SPEC.md` §0.6).
- Empty database → Flyway runs V1. Existing database created by Hibernate before v0.5.0 →
  Flyway only records a `BASELINE` row at version 1 (`baseline-on-migrate`) and changes no
  tables. Check with `SELECT version, type, script, success FROM flyway_schema_history;`.
- The `h2` profile and `@DataJpaTest` keep Flyway disabled and let Hibernate create the schema.
- Rolling back to pre-v0.5.0 code: there is no automatic downgrade. Because V1 changes no
  tables on an existing database, it is enough to drop the history table manually:
  ```sql
  DROP TABLE flyway_schema_history;
  ```
- Schema tooling (temporary MySQL on 127.0.0.1:3307, see `scripts/db/lib.sh`):
  `scripts/db/schema_fingerprint.sh DB` and `scripts/db/compare_schemas.sh DB_A DB_B` produce a
  normalised fingerprint and diff; `scripts/db/verify_schema.sql` is a human-readable check.

---

## Key flows

### Auth (OTP)
1. `POST /api/auth/register` → validates UKM domain, creates an unverified user,
   emails (or logs) a 6-digit OTP (Redis TTL 5 min).
2. `POST /api/auth/verify-otp` → marks verified, returns JWT.
3. `POST /api/auth/resend-otp` → issues a fresh code for an unverified account.
4. `POST /api/auth/login` → JWT (blocked until verified). `GET /api/auth/me` returns the current user.

For real OTP email delivery, set `MAIL_ENABLED=true` plus the SMTP host, port,
username, password, and `MAIL_FROM` values in `.env` or your shell. Gmail works
with an app password; local development can leave mail disabled and read the OTP
from the backend console.

### SecKill (hot path)
1. Admin schedules an event (`POST /api/admin/seckill-events`). A `@Scheduled` task
   (every 10 s) warms Redis stock (`seckill:stock:<eventId>`) and flips status
   PENDING→ACTIVE→ENDED. Allow up to ~10 s after creating an event before it is buyable.
2. `POST /api/seckill/{eventId}/buy` loads the event row (time-window check), then runs
   `seckill_deduct.lua` (atomic check + decrement + per-user guard on
   `seckill:bought:<eventId>`). On success it `XADD`s to the `seckill:orders` stream and
   returns **202 Accepted** + a tracking token. Sold out / already bought / not active
   return **200** with `result` = `SOLD_OUT` / `ALREADY_BOUGHT` / `NOT_ACTIVE`.
   No MySQL **writes** happen on this path.
3. `SeckillStreamConsumer` (every 500 ms, consumer group `seckill-order-consumers`)
   drains the stream, persists the order (idempotent by tracking token) and settles it
   with `FAKE_WALLET`.
4. Client polls `GET /api/seckill/result?token=...` until `PAID`/`FAILED`.

### Orders, cart & payment
- `POST /api/orders` creates a `C2C_ITEM` or `B2C_PRODUCT` order and pays immediately;
  `POST /api/orders/{id}/pay` retries a pending/failed one. The `/cart` page checks out
  by creating one order per cart line.
- Mock Strategy pattern: `FAKE_WALLET` (always succeeds) and `MOCK_FPX` (~90% success).

### Reviews
`GET /api/reviews?targetType=PRODUCT&targetRefId=...` is public and returns reviews
with an average rating. Authenticated users can `POST /api/reviews` after they have a
paid order for the product or item they are reviewing.

### Image upload
`POST /api/upload` (authenticated, multipart, JPEG/PNG/WebP/GIF ≤ 5 MB) stores the file
under `UPLOAD_DIR` and returns `{"url": "/uploads/<uuid>.ext"}`; files are served publicly
at `GET /uploads/**`.

### AI assistant
`POST /api/chat` (authenticated) with `{"message": "..."}` returns `{"reply": "..."}`.
`ChatService` adds up to 10 products whose name/category appears in the message (or the
whole catalogue when it has ≤ 10 products) to the prompt, then calls the LLM. Without
`LLM_API_KEY` it returns a "not configured" placeholder. The controller blocks on the
WebClient `Mono` so the servlet security context is respected (see CHANGELOG v0.4.0).

---

## Tests

- **Unit / slice tests** (`cd backend && ./mvnw -B verify`): 5 tests in 3 classes —
  `UkmEmailValidatorTest`, `PaymentStrategyFactoryTest`, `ReviewRepositoryTest` (`@DataJpaTest`).
- **End-to-end** (`scripts/e2e_test.py`, 8 checks) against a running backend + MySQL + Redis,
  including the SecKill race (N users vs. S units → exactly S orders, no oversell):
  ```bash
  python3 scripts/e2e_test.py --users 60 --stock 20 --log /tmp/ftsm-real.log
  ```
  The script reads OTP codes from the backend log and reuses fixed emails
  (`student0-N@siswa.ukm.edu.my`); clear them between runs on a persistent DB.

Quick manual check with a JWT in `$TOKEN` (a single user is capped at 1 purchase; use
distinct user tokens to test the stock cap):

```bash
seq 1 200 | xargs -P 50 -I{} curl -s -o /dev/null -w "%{http_code}\n" \
  -X POST http://localhost:8080/api/seckill/1/buy \
  -H "Authorization: Bearer $TOKEN" | sort | uniq -c
```

---

## Load testing

`loadtest/` holds k6 scenarios (`throughput.js`, `contention.js`) that judge each SecKill
response by its business `result`, plus `run.sh`, which runs one scenario into its own result
directory and reconciles orders in MySQL and stock in Redis afterwards. Install k6 with
`scripts/tools/install_k6.sh` (k6 2.3.0, sha256-pinned). See [`loadtest/README.md`](loadtest/README.md).
Results under `loadtest/results/_smoke/` are smoke runs that only prove the tooling works —
they are **not** performance numbers; the formal baseline is measured by a person before P3.

---

## Known limitations

Tracked in detail in `docs/UPGRADE_PLAN.md` §1:

- Redis Stream durability depends on Redis persistence (default RDB snapshots only); the
  Lua deduction and the `XADD` are two separate calls.
- The SecKill scheduler runs on every instance — run a **single backend replica** until
  it is guarded by a lock.
- Regular B2C / C2C checkout decrements stock with read-modify-write (no row lock /
  conditional update), so heavy concurrent buying of one normal product can oversell.
- Uploads live on local disk (one volume), so multiple replicas would not share images.

---

## Project layout
```
backend/             Spring Boot API (controllers, services, security, Redis Lua + stream consumer)
frontend/            Vite + React SPA (features: auth, marketplace, seckill, chatbot, admin; pages: cart, orders)
scripts/e2e_test.py  end-to-end + SecKill concurrency verification
scripts/lib/         shared shell helpers (wait.sh: bounded readiness waits)
scripts/db/          temporary MySQL/Redis helpers, V1 export, schema fingerprint/compare
scripts/tools/       pinned tool installers (k6)
loadtest/            k6 scenarios, run/drain/verify/summarize tooling, results
docs/                upgrade plan, master spec, per-phase packages (phases/) and prompts (agent-prompts/)
docker-compose.yml   full stack for deployment
.env.example         secrets template
```

## Configuration
All secrets are environment-driven (see `.env.example` and `backend/src/main/resources/application.yml`):
`DB_URL`/`DB_USERNAME`/`DB_PASSWORD`, `REDIS_HOST`/`REDIS_PORT`/`REDIS_PASSWORD`,
`JWT_SECRET`, `JWT_EXPIRY_MS`, `CORS_ALLOWED_ORIGINS`,
`MAIL_ENABLED`/`SMTP_*`/`MAIL_FROM`, `LLM_API_KEY`/`LLM_BASE_URL`/`LLM_MODEL`,
`SEED_ENABLED`/`SEED_ADMIN_EMAIL`/`SEED_ADMIN_PASSWORD`, `UPLOAD_DIR`, `SERVER_PORT`.

Profiles: default/`dev` (MySQL), `h2` (in-memory DB). Docker Compose sets
`SPRING_PROFILES_ACTIVE=prod`, which currently has no profile-specific overrides and
behaves like the default profile with env-provided settings.
