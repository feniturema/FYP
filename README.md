# FTSM E-Commerce Platform

[![ci](https://github.com/feniturema/FYP/actions/workflows/ci.yml/badge.svg)](https://github.com/feniturema/FYP/actions/workflows/ci.yml)

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
| v0.6.0 P1: Java 21, Spring Boot 3.5.16, virtual threads | Claude Code | 2026-10-04 | Build/runtime upgrade (D1=boot-3.5.16); `scripts/p1/`; no application code changed. |
| v0.7.1 P3: sync comparison mode + benchmark tooling | Claude Code | 2026-10-05 | `SECKILL_MODE=sync` (benchmark only, default stays async), `loadtest/bench/`, three-throughput summary; performance numbers pending (formal measurement by a person). |
| v0.8.0 P4a: catalog-core module split + root Maven Wrapper | Codex (review fixes: Claude Code) | 2026-10-06 | Pure model/repository extraction, root multi-module build, Docker context update, and command-path cleanup; runtime behavior unchanged. |
| v0.9.0 P6a: Testcontainers integration tests + GitHub Actions CI | Claude Code | 2026-10-06 | 11 `*IT` classes (Failsafe) on MySQL 8.0.46 / Kafka 3.9.2 / Redis 8.10.2, zero-test guard `scripts/ci/check_test_reports.py`, `.github/workflows/ci.yml`; no application code changed. |
| v0.10.0 P4b: Spring AI assistant + MCP server + SSE | Claude Code (implementation) / Codex (handoff) | 2026-10-06 | Spring AI 1.1.8 with DeepSeek, five catalogue MCP tools plus local orders, resilient assistant streaming, bounded memory, Testcontainers coverage, Docker Compose and frontend SSE integration. |
| v0.11.0 P5a: FULLTEXT search, `/api/search`, demo catalogue, retrieval eval harness | Claude Code | 2026-10-06 | V3 ngram FULLTEXT indexes, `HybridSearchService` (keyword mode), public `GET /api/search`, 400-product / 200-item demo catalogue under the `demo` profile, debounced frontend search, eval harness with an unlabelled 80-query set. |

v0.4.2 (commit `5f5fae4`) is the pre-upgrade baseline (annotated tag `v0.4.2-baseline`, created in
upgrade phase P0). P0 (v0.5.0) added the Maven Wrapper, Flyway-managed schema and the k6 load-test
tooling; P1 (v0.6.0) moved to Java 21 / Spring Boot 3.5.16; P2 (v0.7.0) replaced the SecKill Redis
Stream with a transactional outbox + Kafka pipeline and made normal checkout race-free; P3 (v0.7.1)
added a synchronous comparison mode and the A/B/C benchmark tooling (numbers pending, see "Performance"); P4a (v0.8.0) split the catalog entities and repositories into `catalog-core` and moved the Maven Wrapper to the repository root. See `CHANGELOG.md` for per-version details, `HANDOFF.md` for the implementation
handoff/status ledger, `docs/UPGRADE_PLAN.md` for the planned v0.5+ upgrade
(Java 21, Outbox + Kafka, Spring AI/MCP, hybrid retrieval, K8s) and
`docs/CHANGE_SPEC.md` for the master implementation spec, with one execution package per phase in
`docs/phases/` and a ready-to-use agent prompt per phase in `docs/agent-prompts/`.
Use [`docs/agent-prompts/START-P0.md`](docs/agent-prompts/START-P0.md) to start the staged implementation; it delegates P0 only and requires the documented evidence and draft-PR gate.

- **Hybrid marketplace** — students list second-hand items (C2C) and an official
  admin store sells products (B2C) with flash-sale **SecKill** events.
- **Redis + Lua SecKill engine** — atomic stock deduction + one-per-user guard in Redis; the
  purchase intent is written to a MySQL **outbox** before `202` is returned, relayed to **Kafka**
  and persisted **asynchronously** by an idempotent listener (one order per buyer per event,
  `sold_count < seckill_stock` as a second oversell guard). The event window comes from a 5 s cache.
- **AI shopping assistant** — Spring AI 1.1.8 with DeepSeek (or another OpenAI-compatible provider),
  MCP-backed catalogue search and local `my_orders` support. The authenticated assistant streams
  token events over SSE and falls back to a deterministic no-key response.
- **UKM-only auth** — `@ukm.edu.my` / `@siswa.ukm.edu.my` + **email OTP** + JWT.

## Tech stack

| Layer | Tech |
|---|---|
| Frontend | Vite 5, React 18, TypeScript, Tailwind CSS, Zustand, React Router 6, Axios |
| Backend | Java 21 (virtual threads), Spring Boot 3.5.16, Spring Data JPA (Hibernate `ddl-auto: validate`), Flyway migrations, Spring Security + JWT (jjwt), Spring Mail, WebClient, springdoc-openapi |
| Data | MySQL 8 (Flyway), Redis 8 (Lua + OTP TTL keys, AOF), Kafka 3.9 (KRaft, SecKill order topic + DLT) |
| AI | Spring AI 1.1.8, DeepSeek (default `deepseek-v4-flash`), MCP client/server, Resilience4j 2.4.0; provider base URL via `DEEPSEEK_BASE_URL` |
| Infra | Docker Compose + Nginx reverse proxy (frontend container) |

---

## Prerequisites

- **JDK 21** (the build targets `release 21`; JDK 17 can no longer compile it). Other JDKs
  (e.g. 25) have not been verified with the Boot-managed Lombok 1.18.46 — use 21. If
  `./mvnw -v` reports another JDK, point Maven at JDK 21:
  ```bash
  export JAVA_HOME=$(/usr/libexec/java_home -v 21)   # macOS
  ```
- No Maven install needed: use the Maven Wrapper `mvnw` (Maven 3.9.11, download verified
  by `distributionSha256Sum`).
- **Node 20+** and npm.
- For local dev: **MySQL 8.0**, **Redis** and **Kafka**. The simplest way is the compose services:
  `docker compose up -d mysql redis kafka` (needs `JWT_SECRET` only for the backend service, so
  these three start with the defaults: MySQL `root`/`root` on 3306, Redis 6379, Kafka 29092).

---

## Quick start — local development

### 1. Backend
```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)   # if needed
# Needs MySQL + Redis + Kafka reachable on localhost (defaults: root/root, db auto-created).
docker compose up -d mysql redis kafka              # from the repo root, if you have nothing local
./mvnw -B -pl backend -am -DskipTests package       # builds catalog-core + backend in one reactor; no install needed
KAFKA_BOOTSTRAP=127.0.0.1:29092 java -jar backend/target/ecommerce-0.0.1-SNAPSHOT.jar
```
`./mvnw spring-boot:run` from the repo root does not work since P4a: the `ftsm-parent` aggregator has no
main class, and `./mvnw -pl backend spring-boot:run` cannot resolve `catalog-core` unless it was installed.
To use `spring-boot:run`, install first (and again after any `catalog-core` change):
`./mvnw -B -pl backend -am -DskipTests install`, then `KAFKA_BOOTSTRAP=127.0.0.1:29092 ./mvnw -pl backend spring-boot:run`.

- API: http://localhost:8080
- Swagger UI: http://localhost:8080/swagger-ui.html
- Seeded admin: `admin@ukm.edu.my` / `Admin@123` (plus 3 sample B2C products)
- **OTP codes** are printed to the backend console (mail is disabled by default).
- To enable a real model, export `LLM_PROVIDER=deepseek` and `LLM_API_KEY` (and optionally `LLM_MODEL` and `DEEPSEEK_BASE_URL`).
  With `LLM_PROVIDER=none` or no key, the assistant still starts and returns a deterministic configuration response.

The in-memory `h2` profile was removed in v0.7.0 (H2 is test-only now): the outbox uses
MySQL-specific SQL (`FOR UPDATE SKIP LOCKED`, `JSON`), so the app needs a real MySQL.

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
- Backend is also exposed directly on `:8080`; MySQL `:3306`, Redis `:6379` and Kafka `:29092`
  are published too — firewall them on a public server. Host ports can be overridden with
  `MYSQL_HOST_PORT`, `REDIS_HOST_PORT`, `KAFKA_HOST_PORT`, `BACKEND_HOST_PORT` and
  `FRONTEND_HOST_PORT` (defaults 3306 / 6379 / 29092 / 8080 / 80). Images are pinned
  (`mysql:8.0.46`, `redis:8.10.2` with AOF, `apache/kafka:3.9.2`). The backend waits for a
  healthy Kafka and creates the topics `seckill.orders` (6 partitions) and `seckill.orders.DLT`.
- Uploaded images persist in the `uploads_data` volume.
- For an existing v0.9.x deployment, add `LLM_PROVIDER=deepseek` and `MCP_DB_PASSWORD` to `.env`
  before restarting. The read-only `ftsm_ro` account is created only when the MySQL volume is first
  initialized; on an existing volume, run `docker compose exec mysql bash /docker-entrypoint-initdb.d/01-readonly-user.sh`.
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
- `V2__seckill_outbox.sql` (v0.7.0) adds `order_outbox`, `orders.seckill_event_id` with the unique
  key `uk_orders_buyer_seckill (buyer_id, seckill_event_id)`, and `seckill_events.sold_count` /
  `reconciled`, backfilling them from existing orders. Upgrading a database with data: follow
  [Upgrading to v0.7.0](#upgrading-an-existing-deployment-to-v070-p2-cutover) first.
- `V3__catalog_fulltext.sql` (v0.11.0) adds the FULLTEXT indexes `ft_products (name, description, category)` and
  `ft_items (title, description, category)` with the **ngram** parser (`ngram_token_size` 2, the MySQL default), so a
  search term shorter than 2 characters (for example a single Chinese character) never matches. It only adds
  indexes: MySQL rebuilds each table while the index is created, which takes negligible time at this project's size.
  Undo by hand only: `ALTER TABLE products DROP INDEX ft_products; ALTER TABLE items DROP INDEX ft_items;` and then
  delete the version 3 row from `flyway_schema_history`.
- `@DataJpaTest` slice tests run on H2 with Flyway disabled and let Hibernate create the schema.
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

### SecKill (hot path, since v0.7.0)
1. Admin schedules an event (`POST /api/admin/seckill-events`). A `@Scheduled` task
   (every 10 s) warms Redis stock with `SET NX` (`seckill:stock:{<eventId>}`; the braces are a
   Redis Cluster hash tag) and flips status PENDING→ACTIVE→ENDED. Allow up to ~10 s after
   creating an event before it is buyable. Editing a PENDING event drops its Redis keys.
2. `POST /api/seckill/{eventId}/buy` checks the event window from a 5 s Caffeine cache, runs
   `seckill_deduct.lua` (atomic check + decrement + per-user guard on `seckill:bought:{<eventId>}`),
   then inserts the purchase intent into `order_outbox` (autocommit). Responses (body is always
   `{result, trackingToken, message}`):

   | result | HTTP |
   | --- | --- |
   | `ACCEPTED` (+ tracking token) | **202** |
   | `SOLD_OUT`, `ALREADY_BOUGHT`, `NOT_ACTIVE` | **409** (was 200 before v0.7.0) |
   | `UNAVAILABLE` (the outbox write could not be confirmed) | **503** |

   If the outbox insert fails and the row is confirmed absent, the Redis slot is given back
   (`seckill_rollback.lua`). If it cannot even be checked (database down), nothing is given back
   and an `UNCERTAIN` error is logged — the user should check "My Orders" before retrying.
3. `OutboxRelay` (every 100 ms) publishes NEW rows to Kafka topic `seckill.orders` (key = event id)
   and marks them SENT only after every send is acknowledged.
4. `SeckillOrderListener` (consumer group `seckill-order-writer`) writes the order, bumps
   `sold_count` under `sold_count < seckill_stock` and settles it with `FAKE_WALLET`, all in one
   transaction. Redeliveries hit the unique keys and are skipped (`duplicate … skipped`); any other
   failure is retried 3 times and then sent to `seckill.orders.DLT`.
5. Client polls `GET /api/seckill/result?token=...` until `PAID`/`FAILED` (`PENDING` until written).
6. `SeckillReconciler` (every 60 s) gives each ENDED event a final verdict 2 min after it ends,
   comparing outbox, orders, `sold_count` and Redis; mismatches (e.g. lost slots = undersell) are
   logged and counted (`seckill.reconcile.*`), never auto-repaired. `OutboxJanitor` deletes SENT
   rows of reconciled events after 3 days. Failure semantics: `docs/phases/P2.md` §6.1.

### Orders, cart & payment
- `POST /api/orders` creates a `C2C_ITEM` or `B2C_PRODUCT` order and pays immediately;
  `POST /api/orders/{id}/pay` retries a pending/failed one. Stock / item status is changed with a
  conditional `UPDATE` in the same transaction (`totalStock > 0`, `status = ACTIVE`), so concurrent
  buyers cannot oversell and a failed payment rolls the change back (400 when nothing is left). The `/cart` page checks out
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
`POST /api/assistant/stream` (authenticated), with JSON `{"message":"...","conversationId":"optional-id"}`, returns an SSE stream with `token`,
`done`, and `error` events. `POST /api/chat` remains as a compatibility endpoint and returns
the aggregated assistant reply. The assistant uses Spring AI `ChatClient`, five catalogue tools
served by the local MCP server (`search_products`, `search_secondhand_items`, `get_product_detail`,
`get_stock`, `list_flash_sales`) and a local `my_orders` tool. Tool calls carry the authenticated
user id through the request context. Conversation memory is bounded and process-local
(maximum 1,000 conversations, two-hour idle expiry); no-key mode is deterministic and does
not contact an external provider.

### Catalogue search (since v0.11.0)
`GET /api/search` is public. Parameters: `q` (required, 1–200 characters after trimming), `maxPrice` (0–1000000, at
most 2 decimals), `category` (one of the 10 `CatalogCategory` values, case-insensitive: Apparel, Accessories,
Lifestyle, Electronics, Books, Stationery, Sports, Food, Home, Beauty), `type` (`product`, `item` or `all`, default
`all`), `limit` (1–20, default 10). Any invalid value is a 400 with a `message`. Products and ACTIVE second-hand
items are recalled with MySQL FULLTEXT (top 20 each); each table's scores are divided by that table's best score
before merging, then ordered by normalised score, raw score, product before item, id. Response:
`{"query", "effectiveMode": "KEYWORD", "hits": [{"ref": "product:1001", "type", "id", "title", "price", "category",
"imageUrl", "score"}]}`. `mode` (`keyword` only until P5b; anything else is 400) and `debug=true` (adds per-hit ranks,
model token usage and latency) only take effect under the `dev` or `test` profile and are ignored otherwise.
The Marketplace search box calls it 300 ms after typing stops, cancels the previous request and ignores late
responses; with an empty box the page shows the normal browse list. The MCP tools `search_products` /
`search_secondhand_items` use the same FULLTEXT recall (an unknown `category` is ignored with a note in the result).

### Demo catalogue (`demo` profile, since v0.11.0)
`backend/src/main/resources/demo/catalog.json` (400 products, ids 1001–1400; 200 items, ids 5001–5200; 10 categories;
English / Malay / Chinese 6:2:2) is generated by `python3 scripts/p5a/gen_catalog.py` and is byte-identical on every
run. With `SPRING_PROFILES_ACTIVE` containing `demo` the backend imports it at startup, in one transaction, together
with 20 sellers `seller01`–`seller20@siswa.ukm.edu.my`. Guards, checked before anything is written (startup fails
otherwise): `APP_DEMO_TARGET_DB` must equal the database the datasource is connected to; the compose default
`ftsm_ecommerce` also needs `APP_DEMO_ALLOW_DEFAULT_DB=true`; `SEED_DEMO_PASSWORD` (the sellers' password) needs at
least 12 characters. Re-running is idempotent (`inserted 0, unchanged 600`). A row whose content differs from the
file is a conflict: `APP_DEMO_ON_CONFLICT=fail` (default) rolls the whole import back and stops startup;
`skip` keeps the row, logs a WARN and writes `${java.io.tmpdir}/demo-import-report.json`. Stock and item status are
not compared, so orders placed on demo data do not block a restart. After the import, both tables'
`AUTO_INCREMENT` is moved to at least 10000. Only one instance may import at a time. Example:
```bash
SPRING_PROFILES_ACTIVE=dev,demo APP_DEMO_TARGET_DB=ftsm_demo SEED_DEMO_PASSWORD=$(openssl rand -hex 12) ...
```
Remove the demo data:
```sql
DELETE FROM items WHERE id BETWEEN 5001 AND 9999;
DELETE FROM products WHERE id BETWEEN 1001 AND 4999;
DELETE FROM users WHERE email REGEXP '^seller(0[1-9]|1[0-9]|20)@siswa\\.ukm\\.edu\\.my$';
```

### Retrieval evaluation
`eval/run_retrieval_eval.py` reports Recall@5, MRR@10, p50 latency and cost per 1000 queries for `GET /api/search`;
see `eval/README.md`. The 80 queries in `eval/queries.jsonl` are **not labelled yet** (`relevant` is empty, the
script exits 3); a person labels them after P5a is merged, and P5b waits for that.

---

## Tests

- **Unit / slice tests** (`./mvnw -B verify`): 134 tests in 24 classes, including the
  P2 pipeline (`SeckillServiceBuyTest`, `OutboxPublisherTest`, `SeckillOrderListenerTest`,
  `KafkaConfigTest`, `SeckillReconcilerTest`, `SeckillEventCacheTest`, `SeckillControllerTest`) and
  H2 transaction tests (`SeckillOrderWriterH2Test`, `OrderServiceRollbackTest`). No broker needed.
- **Integration tests** (`*IT`, run by Failsafe in the same `./mvnw -B verify`; since P6a/P4b/P5a): 18 classes /
  42 cases on real MySQL 8.0.46, Kafka 3.9.2 and Redis 8.10.2 containers (Testcontainers 1.21.4), covering
  the SecKill pipeline, normal-checkout races, reconciliation, Flyway history, assistant startup/configuration,
  authenticated SSE security, resilience fallbacks, MCP tool flow, the MCP server, FULLTEXT search and the demo
  catalogue import. Needs Docker; see
  "Running integration tests".
- **P2 acceptance** (`scripts/p2/acceptance.sh`): e2e, backend crash, Kafka pause, offset replay,
  unconfirmable outbox write, normal-checkout race, reconciliation and a k6 smoke, all inside the
  compose project `ftsm-p2-acc`; evidence in `scripts/p2/evidence/`.
- **End-to-end** (`scripts/e2e_test.py`, 8 checks) against a running backend + MySQL + Redis + Kafka,
  including the SecKill race (N users vs. S units → exactly S orders, no oversell):
  ```bash
  python3 scripts/e2e_test.py --users 60 --stock 20 --log /tmp/ftsm-real.log
  ```
  The script reads OTP codes from the backend log and reuses fixed emails
  (`student0-N@siswa.ukm.edu.my`); clear them between runs on a persistent DB.
  Redis keeps `seckill:bought:{<eventId>}` across database resets: when you pair a fresh database
  with a Redis that already ran the e2e, flush Redis first, or earlier winners (same user and event
  ids) come back as `ALREADY_BOUGHT` and show up as `others` in the race summary.

### Running integration tests

Docker (≥ 24) must be running; Testcontainers starts and removes the containers itself.

```bash
./mvnw -B verify                                                       # unit tests + all *IT
python3 scripts/ci/check_test_reports.py --expect scripts/ci/expected-tests.json   # zero-test guard
./mvnw -B verify -Dit.test=MigrationIT                                 # one IT class
```

`check_test_reports.py` fails when an expected IT class has no report, runs fewer tests than listed in
`scripts/ci/expected-tests.json`, or when any test is skipped. A phase that adds an IT adds it to that file.
CI (`.github/workflows/ci.yml`) runs the same on every PR, builds the frontend and the Docker images
(without pushing), and publishes the images to GHCR only on pushes to `main`.

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
`scripts/tools/install_k6.sh` (k6 2.3.0, sha256-pinned; Linux only — elsewhere use the pinned
image `grafana/k6:2.3.0`). From v0.7.0 rejections are HTTP 409 (`--reject-status 409`, the new
default) and `--drain outbox` waits for the Kafka pipeline. See [`loadtest/README.md`](loadtest/README.md).
Results under `loadtest/results/_smoke/` are smoke runs that only prove the tooling works —
they are **not** performance numbers. P3 added the three-configuration benchmark
(`loadtest/bench/`, see "Performance" below and [`loadtest/README.md`](loadtest/README.md#three-configurations-p3)).

---

## Performance

**Status: formal measurement pending** (H1 in `docs/phases/P3.md` §7.3, done by a person on a
dedicated machine and submitted as a separate PR). Until then every value below is `TBD` and this
README makes no performance claim. The smoke runs in `loadtest/results/_smoke/` only prove the
tooling works and are not performance data.

Configurations (same host, same JDK 21, same JVM options `-Xms2g -Xmx2g -XX:+UseG1GC`, own
database each, MySQL / Redis / Kafka from the `ftsm-p3-bench` compose project):

| Config | Backend | SecKill write path after the Redis Lua deduction |
| --- | --- | --- |
| A-baseline | tag `v0.4.2-baseline` | Redis Stream, consumed by the old stream consumer |
| B-sync | current code, `SECKILL_MODE=sync` (benchmark only) | order + `sold_count` + payment in the request thread |
| C-async | current code, default `async` | outbox row (autocommit) → relay → Kafka → listener |

Metric definitions (`loadtest/summarize.py`, `docs/phases/P3.md` §6.3): `requestRps` = steady-phase
requests ÷ steady seconds; `acceptedRps` = steady-phase `ACCEPTED` ÷ steady seconds (409 / sold-out
rejections excluded); `persistedRps` = orders ÷ (last − first order `created_at`); `drainSeconds` =
time until everything accepted is persisted (whole seconds); latency = steady-phase
`http_req_duration` (contention runs: whole run, p50/p95 only). A run
counts only if k6 passed all thresholds (no dropped iterations, accept rate ≥ 0.99), the drain
succeeded and orders equal accepted requests.

Throughput (sustainable rate = highest step where all three runs were valid, p99 < 1000 ms and
errors < 1 %):

| Config | Sustainable rate | requestRps | acceptedRps | persistedRps | drainSeconds | p50 (ms) | p95 (ms) | p99 (ms) | Valid runs |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| A-baseline | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD |
| B-sync | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD |
| C-async | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD |

Contention (stock 100, 5000 buyers, 1000 VUs, 3 runs per config):

| Config | Orders = stock | Duplicates | persistedRps | drainSeconds | p50 (ms) | p95 (ms) | Valid runs |
| --- | --- | --- | --- | --- | --- | --- | --- |
| A-baseline | TBD | TBD | TBD | TBD | TBD | TBD | TBD |
| B-sync | TBD | TBD | TBD | TBD | TBD | TBD | TBD |
| C-async | TBD | TBD | TBD | TBD | TBD | TBD | TBD |

Environment: TBD (filled from [`loadtest/bench/ENVIRONMENT.md`](loadtest/bench/ENVIRONMENT.md)).

### Design trade-offs (SecKill)

- **Why the hot path still does one MySQL insert.** A `202` must mean the purchase intent is
  durable. Redis alone is not enough (AOF `everysec` can lose about the last second of
  deductions), and writing to Kafka from the request would be a second, non-transactional write
  next to Redis. One autocommit row in `order_outbox` is the cheapest durable record; the relay and
  Kafka then run off the request path, so a Kafka outage delays orders but does not reject buyers.
  Config B (sync) writes the whole order transaction in the request thread instead; the A/B/C
  comparison measures what the outbox costs and saves.
- **When it undersells (it never oversells).** Overselling is blocked twice: the Lua deduction
  with its per-user guard in Redis, then `sold_count < seckill_stock` and the unique key
  `(buyer_id, seckill_event_id)` in MySQL. Units can be lost (undersold) in four rare windows: a
  crash between the Redis deduction and the outbox insert, an outbox insert whose outcome cannot be
  confirmed (logged `UNCERTAIN`, nothing given back), a failed compensation, or a consumer that
  never catches up.
- **How it is reconciled.** `SeckillReconciler` gives every ended event a final verdict by
  comparing the outbox, the orders, `sold_count` and the Redis counter; differences raise
  `seckill.reconcile.mismatch` / `seckill.reconcile.incomplete`. Nothing is repaired
  automatically; an operator decides from the logged ids (`docs/phases/P2.md` §6.1, §6.8).

---

## Known limitations

Tracked in detail in `docs/UPGRADE_PLAN.md` §1:

- SecKill can **undersell** (never oversell) in four rare windows: a crash between the Redis
  deduction and the outbox insert, an outbox insert whose outcome cannot be confirmed, a failed
  compensation, or a consumer that never catches up. The reconciler reports them; nothing is
  repaired automatically (`docs/phases/P2.md` §6.1, §6.8).
- If publishing to the DLT itself fails, the record is not committed: it is retried and its partition is
  blocked until the DLT is reachable again (proven by `DltPublishFailureIT` in P6a). With the broker
  default `auto.create.topics.enable=true` (also in `docker-compose.yml`), a deleted DLT is simply
  recreated with broker defaults on the next publish, so that failure needs the broker to be unreachable.
- The scheduled tasks (warm-up, relay, reconciler, janitor) run on every instance — run a
  **single backend replica** until they are guarded by a lock (P6b).
- Uploads live on local disk (one volume), so multiple replicas would not share images.
- Assistant memory and tool-call records are process-local and bounded; use a shared store before running multiple backend replicas.
- Real-model assistant acceptance (P4b E1–E2) requires a valid `LLM_API_KEY`; the committed tests use the deterministic no-key/fake-model path. The optional MCP Inspector screenshot (E3) is still pending.
- The local machine used for this verification had Node 24.12.0; CI pins Node 20.20.2.
- Search is keyword-only (FULLTEXT ngram): a synonym or another language with no shared characters does not match.
  Vector / RRF / rerank modes are P5b, which also needs the labelled eval set.

---

## Project layout
```
backend/             Spring Boot API (controllers, services, security, Redis Lua, outbox relay + Kafka listener, assistant)
mcp-server/          Spring AI MCP server exposing the catalogue tools
frontend/            Vite + React SPA (features: auth, marketplace, seckill, chatbot, admin; pages: cart, orders)
scripts/e2e_test.py  end-to-end + SecKill concurrency verification
scripts/lib/         shared shell helpers (wait.sh: bounded readiness waits)
scripts/db/          temporary MySQL/Redis helpers, V1 export, schema fingerprint/compare
scripts/p2/          P2 acceptance, normal-checkout race, cutover precheck, evidence
scripts/p4b/         P4b acceptance and evidence helpers
scripts/p5a/         demo catalogue generator (gen_catalog.py) and P5a acceptance evidence
scripts/tools/       pinned tool installers (k6)
loadtest/            k6 scenarios, run/drain/verify/summarize tooling, results
eval/                assistant and retrieval evaluation (queries, harness, pricing template)
mysql/               Compose bootstrap SQL for the read-only assistant account
docs/                upgrade plan, master spec, per-phase packages (phases/) and prompts (agent-prompts/)
docker-compose.yml   full stack for deployment
.env.example         secrets template
```

## Configuration
All secrets are environment-driven (see `.env.example` and `backend/src/main/resources/application.yml`):
`DB_URL`/`DB_USERNAME`/`DB_PASSWORD`, `REDIS_HOST`/`REDIS_PORT`/`REDIS_PASSWORD`,
`KAFKA_BOOTSTRAP` (default `localhost:9092`; `kafka:9092` inside compose, `127.0.0.1:29092` from the
host), `KAFKA_REPLICAS` (topic replication factor, default `1`),
`JWT_SECRET`, `JWT_EXPIRY_MS`, `CORS_ALLOWED_ORIGINS`,
`MAIL_ENABLED`/`SMTP_*`/`MAIL_FROM`, `LLM_PROVIDER`/`LLM_API_KEY`/`DEEPSEEK_BASE_URL`/`LLM_MODEL`,
`MCP_SERVER_URL`/`MCP_CLIENT_ENABLED`/`MCP_DB_USERNAME`/`MCP_DB_PASSWORD`/`MCP_HOST_PORT`,
`SEED_ENABLED`/`SEED_ADMIN_EMAIL`/`SEED_ADMIN_PASSWORD`, `UPLOAD_DIR`, `SERVER_PORT`,
`APP_DEMO_TARGET_DB`/`APP_DEMO_ALLOW_DEFAULT_DB`/`APP_DEMO_ON_CONFLICT`/`SEED_DEMO_PASSWORD` (demo profile only),
`VIRTUAL_THREADS` (default `true`: Tomcat requests, `@Scheduled` and `@Async` run on Java 21
virtual threads; `false` restores platform threads), `DB_POOL_SIZE` (Hikari maximum pool size,
default `20`), `SECKILL_MODE` (`async`, the default and the only production setting; `sync` writes
the order in the request thread and exists only for the P3 benchmark comparison). In the container, `JAVA_OPTS` (default `-XX:MaxRAMPercentage=75`) is passed to
`java`; the runtime image is pinned to `eclipse-temurin:21.0.12.1_1-jre-noble`.

Profiles: default/`dev` (MySQL; also enables the assistant debug endpoints and the `mode`/`debug` search
parameters), `demo` (imports the demo catalogue, see above). Docker Compose sets
`SPRING_PROFILES_ACTIVE=prod`, which currently has no profile-specific overrides and
behaves like the default profile with env-provided settings.

---

## Upgrading an existing deployment to v0.7.0 (P2 cutover)

Only needed when the database already holds data (`docs/phases/P2.md` §10). A person runs it:

1. Read-only precheck, before deploying — it must print `precheck: PASS`:
   ```bash
   python3 scripts/p2/precheck_cutover.py --mysql <target> --redis <target>   # targets: scripts/db/lib.sh
   ```
   It checks that no event is running or starts within 30 min, that the old `seckill:orders`
   stream is fully consumed and every entry has its order, that there are no duplicate SECKILL
   orders per buyer and event, and that no open event has more orders than stock. It prints the
   `mysqldump --single-transaction` command: take that backup.
2. Stop the backend (announce a maintenance window), deploy v0.7.0; Flyway runs V2 on start.
3. Confirm `SELECT version, success FROM flyway_schema_history` shows `2 | 1`.
4. Archive the old stream (kept, not deleted): `RENAME seckill:orders seckill:orders:archive:<yyyyMMdd>`.
5. Check that events which have not started were re-warmed under `seckill:stock:{<id>}`, then
   reopen traffic.

V2 is not transactional (MySQL DDL): if it fails half-way, restore the backup and go back to the
v0.6.0 code. There is no automatic downgrade.
