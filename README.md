# FTSM E-Commerce Platform

A high-concurrency campus e-commerce platform for the FTSM / UKM community.

## Traceability

| Version / scope | Authoring agent | Date | Notes |
|---|---|---|---|
| v0.1.0 foundation scaffold + verified SecKill core | Claude Code (Opus 4.8) | 2026-05-31 | Original project structure, core backend/frontend, e2e SecKill verification, and initial docs. |
| v0.2.0 P1/P2 feature completion | Codex (GPT-5) | 2026-05-31 | Admin SecKill management, order detail/pay-later, OTP resend docs/UI/API, reviews, detail pages, tests, changelog updates, and git initialization. |

See `CHANGELOG.md` for per-version details and `HANDOFF.md` for the implementation handoff/status ledger.

- **Hybrid marketplace** — students list second-hand items (C2C) and an official
  admin store sells products (B2C) with flash-sale **SecKill** events.
- **Redis + Lua SecKill engine** — atomic stock deduction in Redis, orders persisted
  asynchronously via a **Redis Stream** consumer so MySQL is never the bottleneck.
- **AI shopping assistant** — async Gemini calls via WebClient (non-blocking).
- **UKM-only auth** — `@ukm.edu.my` / `@siswa.ukm.edu.my` + **email OTP** + JWT.

## Tech stack

| Layer | Tech |
|---|---|
| Frontend | Vite, React 18, TypeScript, Tailwind CSS, Zustand, React Router, Axios |
| Backend | Java 17, Spring Boot 3.3, Spring Data JPA, Spring Security + JWT, Spring Mail, WebClient |
| Data | MySQL 8, Redis 7 (Lua + Streams) |
| Infra | Docker Compose + Nginx reverse proxy |

---

## Prerequisites

- **JDK 17 or 21** (the build is pinned to Java 17). ⚠️ **Do not build with JDK 25** —
  Lombok's annotation processor is not yet compatible and the build fails with
  `TypeTag :: UNKNOWN`. If `mvn -version` reports JDK 25, point Maven at JDK 21:
  ```bash
  export JAVA_HOME=$(/usr/libexec/java_home -v 21)   # macOS
  ```
- **Node 20+** and npm.
- For local dev: a running **MySQL 8** and **Redis 7** (or use Docker, below).

---

## Quick start — local development

### 1. Backend
```bash
cd backend
export JAVA_HOME=$(/usr/libexec/java_home -v 21)   # if needed
# Needs MySQL + Redis reachable on localhost (defaults: root/root, db auto-created).
mvn spring-boot:run
```
- API: http://localhost:8080
- Swagger UI: http://localhost:8080/swagger-ui.html
- Seeded admin: `admin@ukm.edu.my` / `Admin@123`
- **OTP codes** are printed to the backend console (mail is disabled by default).

> No MySQL handy? Run with the in-memory H2 profile (still needs Redis):
> ```bash
> SPRING_PROFILES_ACTIVE=h2 mvn spring-boot:run
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
cp .env.example .env     # edit secrets (JWT_SECRET, DB_PASSWORD, GEMINI_API_KEY, SMTP…)
docker compose up -d --build
```
- Public site: `http://<server-ip>/`  (Nginx serves the SPA and proxies `/api`)
- For HTTPS on a real domain, terminate TLS at an outer Nginx/Caddy or add
  Certbot/Let's Encrypt in front of the `frontend` container.

---

## Key flows

### Auth (OTP)
1. `POST /api/auth/register` → validates UKM domain, creates an unverified user,
   emails (or logs) a 6-digit OTP (Redis TTL 5 min).
2. `POST /api/auth/verify-otp` → marks verified, returns JWT.
3. `POST /api/auth/resend-otp` → issues a fresh code for an unverified account.
4. `POST /api/auth/login` → JWT (blocked until verified).

For real OTP email delivery, set `MAIL_ENABLED=true` plus the SMTP host, port,
username, password, and `MAIL_FROM` values in `.env` or your shell. Gmail works
with an app password; local development can leave mail disabled and read the OTP
from the backend console.

### SecKill (hot path)
1. Admin schedules an event (`/admin/seckill-events`). A scheduler warms Redis stock
   (`seckill:stock:{eventId}`) and flips status PENDING→ACTIVE→ENDED.
2. `POST /api/seckill/{eventId}/buy` runs `seckill_deduct.lua` (atomic check + decrement +
   per-user guard). On success it `XADD`s to the `seckill:orders` stream and returns
   **202 Accepted** + a tracking token. **MySQL is never touched on this path.**
3. `SeckillStreamConsumer` drains the stream and persists the order (idempotent by token).
4. Client polls `GET /api/seckill/result?token=...` until `PAID`/`FAILED`.

### Payment
Mock Strategy pattern: `FAKE_WALLET` (always succeeds) and `MOCK_FPX` (~90% success).

### Reviews
`GET /api/reviews?targetType=PRODUCT&targetRefId=...` is public and returns reviews
with an average rating. Authenticated users can `POST /api/reviews` after they have a
paid order for the product or item they are reviewing.

---

## Verifying the SecKill concurrency guarantee

With the stack running and an ACTIVE event of stock `N`, fire more than `N` concurrent
buys and confirm exactly `N` succeed, none oversell, and duplicate buys per user are
rejected (`ALREADY_BOUGHT`). Example with a JWT in `$TOKEN`:

```bash
seq 1 200 | xargs -P 50 -I{} curl -s -o /dev/null -w "%{http_code}\n" \
  -X POST http://localhost:8080/api/seckill/1/buy \
  -H "Authorization: Bearer $TOKEN" | sort | uniq -c
```
(Use distinct user tokens to test the stock cap; a single user is capped at 1.)

---

## Project layout
```
backend/   Spring Boot API (controllers, services, security, Redis Lua + stream consumer)
frontend/  Vite + React SPA (features: auth, marketplace, seckill, chatbot, admin)
docker-compose.yml   full stack for deployment
.env.example         secrets template
```

## Configuration
All secrets are environment-driven (see `.env.example` and `backend/src/main/resources/application.yml`):
`DB_*`, `REDIS_*`, `JWT_SECRET`, `JWT_EXPIRY_MS`, `CORS_ALLOWED_ORIGINS`,
`MAIL_ENABLED`/`SMTP_*`/`MAIL_FROM`, `GEMINI_API_KEY`/`GEMINI_MODEL`,
`SEED_ADMIN_EMAIL`/`SEED_ADMIN_PASSWORD`.
