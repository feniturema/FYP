# FTSM 升级修改规格（逐文件）

基于 `main` @ v0.4.3 的代码编写。设计理由见 `docs/UPGRADE_PLAN.md`，本文只写**怎么改**：每个阶段改哪些文件、改成什么样、怎么测、怎么验收。

---

## 0. 给执行 agent 的说明

### 0.1 使用方式

- 一个阶段 = 一个 agent 会话 = 一个 PR。按 §0.3 的顺序执行；不要在一个 PR 里做两个阶段。
- 给 agent 的提示词模板：

  > 阅读 `docs/CHANGE_SPEC.md` 的 §0 和 §Pn，以及 `HANDOFF.md` §4 的代码约定。只实现 §Pn 列出的改动，不做 “不在本阶段范围” 里列出的事。完成后逐条执行 §Pn 的 “验收” 并把命令和输出贴进 PR 描述。规格和代码对不上时，以代码现状为准做最小调整，并在 PR 描述里写明偏差。

- 规格里的代码是**目标形态的骨架**，不是可直接粘贴的最终代码；import、getter、异常处理按现有风格补全。
- 依赖版本：凡是由 Spring Boot / Spring AI BOM 管理的依赖**不写版本号**；未被管理的，取动手当天 Maven Central 上的最新稳定版，并在 PR 里注明。

### 0.2 全局约定（所有阶段都适用）

| 项 | 约定 |
| --- | --- |
| 包结构 | 沿用 `controller / service / repository / model / dto / config / consumer / security / utils / exception`，根包 `my.edu.ukm.ftsm.ecommerce`。新增的 Kafka 监听器放 `consumer/`，Kafka/Redis/Resilience 配置放 `config/` |
| DTO | 按领域放进现有的 `XxxDtos` 记录类集合，不新建零散 DTO 文件 |
| 错误 | 业务错误抛 `BusinessException`(400) / `ResourceNotFoundException`(404)；不要在 controller 里手写错误 body |
| Redis key | 全部集中在 `utils/RedisKeys` |
| 配置 | 新配置放 `application.yml` 的 `app.*` 下，并支持环境变量覆盖；新增环境变量同步到 `.env.example`、`docker-compose.yml`、README 的 Configuration 一节 |
| 数据库 | P0 之后，**任何**表结构变更都写成 Flyway 迁移 `backend/src/main/resources/db/migration/V{n}__{desc}.sql`，禁止依赖 Hibernate 自动建表；已合并的迁移文件不可修改 |
| 文档 | 每个 PR 必须：在 `CHANGELOG.md` 顶部加版本条目（版本号见 §0.3）；更新 `HANDOFF.md` §1 状态表和 §5 API 列表；行为变化同步 README |
| 测试 | 每个 PR 结束时 `./mvnw -B verify` 和 `cd frontend && npm run build` 都必须通过 |
| 密钥 | 任何 key 都只经环境变量传入；不得提交 `.env` |

### 0.3 阶段、版本号与依赖

| 阶段 | 版本 | 前置 | 内容 |
| --- | --- | --- | --- |
| P0 | v0.5.0 | — | 基线 tag、Maven Wrapper、Flyway、k6 脚本与基线 |
| P1 | v0.6.0 | P0 | Java 21、Boot 3.5、虚拟线程 |
| P2 | v0.7.0 | P1 | Outbox + Kafka 取代 Redis Stream；普通下单竞态修复 |
| P3 | v0.7.1 | P2 | 三配置压测 + 结果入库 |
| P4a | v0.8.0 | P1 | 拆 Maven 多模块（不改行为） |
| P4b | v0.9.0 | P4a | MCP server、Spring AI 助手、SSE、Resilience4j |
| P5a | v0.10.0 | P4b | 演示目录数据、FULLTEXT、`/api/search`、评测框架、评测查询草稿 |
| — | — | P5a | **人工**：标注 `eval/queries.jsonl` 并单独提交 |
| P5b | v0.11.0 | 人工标注 | 向量检索、RRF、LLM 重排、跑分 |
| P6a | v0.12.0 | P2 | Testcontainers 集成测试 + GitHub Actions |
| P6b | v0.13.0 | P4b、P6a | ShedLock、K8s、OpenTelemetry、Grafana |
| P7 | v0.14.0 | P4b | GPT-4o Vision 上架助手（可选） |

可并行：P4a 合并后，P2→P3 与 P4b→P5 可同时进行；P6a 只依赖 P2。**P2 与 P4a 不能同时进行**（P4a 会移动 P2 要修改的实体文件）：要么 P2 完全合并后再做 P4a，要么 P4a 合并后再开 P2。

---

## P0 · 基线与工程准备

**目标**：冻结旧版本供对比；补齐构建与迁移工具；写好压测脚本并测出基线。不改任何业务行为。

### P0.1 基线 tag

```bash
git tag v0.4.0-baseline 5f5fae4
git push origin v0.4.0-baseline
```

`5f5fae4` 是 v0.4.2 的代码提交（v0.4.3 只改了文档和 compose 的环境变量名，不影响性能）。

### P0.2 文件清单

| 操作 | 路径 | 说明 |
| --- | --- | --- |
| 新增 | `backend/mvnw`、`backend/mvnw.cmd`、`backend/.mvn/wrapper/*` | `cd backend && mvn -N wrapper:wrapper -Dmaven=3.9.11` 生成；P4a 时移到仓库根 |
| 修改 | `backend/pom.xml` | 加 `org.flywaydb:flyway-core`、`org.flywaydb:flyway-mysql`（Boot 3.x 没有 flyway starter，两者都由 Boot 管理版本） |
| 新增 | `backend/src/main/resources/db/migration/V1__baseline.sql` | 由 Hibernate 生成的现有 schema 导出，见 P0.3 |
| 修改 | `backend/src/main/resources/application.yml` | Flyway 配置、`ddl-auto: validate`、h2 profile 关闭 Flyway |
| 修改 | `backend/src/test/java/.../repository/ReviewRepositoryTest.java` | properties 加 `spring.flyway.enabled=false` |
| 新增 | `loadtest/` 下全部文件 | 见 P0.4 |
| 修改 | `README.md`、`HANDOFF.md`、`CHANGELOG.md` | 用 `./mvnw`；Flyway 说明；loadtest 说明 |

### P0.3 Flyway

`application.yml`（默认 profile 部分）：

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: validate          # 原为 update
  flyway:
    enabled: true
    locations: classpath:db/migration
    baseline-on-migrate: true     # 已有的、由 Hibernate 建好的库：直接记为 V1
    baseline-version: 1
```

`h2` profile 追加：

```yaml
  flyway:
    enabled: false                # H2 继续由 Hibernate create-drop 建表
```

生成 `V1__baseline.sql` 的步骤（必须从真实 MySQL 导出，不要手写；Hibernate 6 在 MySQL 上会把 `@Enumerated(STRING)` 建成 `enum(...)` 列，手写容易对不上 `validate`）：

```bash
docker run -d --name v1-mysql -e MYSQL_ROOT_PASSWORD=root -e MYSQL_DATABASE=ftsm_ecommerce -p 3307:3306 mysql:8.0
# 在 P0 改动之前的代码上（ddl-auto=update）启动一次，关掉种子数据
cd backend && DB_URL='jdbc:mysql://localhost:3307/ftsm_ecommerce?useSSL=false&allowPublicKeyRetrieval=true' \
  DB_PASSWORD=root SEED_ENABLED=false mvn spring-boot:run   # 启动成功后 Ctrl-C
docker exec v1-mysql mysqldump -uroot -proot --no-data --skip-comments --skip-add-drop-table \
  --skip-set-charset ftsm_ecommerce > src/main/resources/db/migration/V1__baseline.sql
```

导出后手工清理：删掉 `/*!40101 ... */` 之类的会话设置行和 `AUTO_INCREMENT=n` 表选项，保留 `CREATE TABLE` 语句。应包含 6 张表：`users`、`items`、`products`、`seckill_events`、`orders`、`reviews`。

### P0.4 压测脚本 `loadtest/`

```text
loadtest/
├── README.md               # 运行方法、环境记录模板
├── lib/jwt.js              # k6 内签 HS256
├── throughput.js
├── contention.js
├── setup_event.py          # 用管理员账号建商品+活动，打印 EVENT_ID
├── reset.sh                # 清 Redis + 清秒杀订单
├── verify.sql
├── summarize.py            # 汇总 results/ 下的 JSON，输出 Markdown 表
└── results/
    ├── _smoke/             # agent 在自己容器里跑的冒烟结果，不作数
    └── .gitkeep
```

`lib/jwt.js`：`JwtAuthFilter` 只校验签名、读取 `sub` / `email` / `role`，不查库，所以可以直接签。

```javascript
import crypto from 'k6/crypto';
import encoding from 'k6/encoding';

const HEADER = encoding.b64encode(JSON.stringify({ alg: 'HS256', typ: 'JWT' }), 'rawurl');

export function signJwt(userId, secret) {
  const now = Math.floor(Date.now() / 1000);
  const payload = encoding.b64encode(JSON.stringify({
    sub: String(userId), email: `load${userId}@siswa.ukm.edu.my`, role: 'STUDENT',
    name: `Load ${userId}`, iat: now, exp: now + 3600,
  }), 'rawurl');
  const sig = crypto.hmac('sha256', secret, `${HEADER}.${payload}`, 'base64rawurl');
  return `${HEADER}.${payload}.${sig}`;
}
```

`throughput.js`：拆成 `ramp` 和 `steady` 两个 scenario，只对 `steady` 定阈值，`--summary-export` 才会输出稳定阶段的子指标。

```javascript
import http from 'k6/http';
import exec from 'k6/execution';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { signJwt } from './lib/jwt.js';

const LEGACY = __ENV.LEGACY === '1';            // 基线版本：拒绝时返回 200，新版本返回 409
http.setResponseCallback(http.expectedStatuses(...(LEGACY ? [200, 202] : [202, 409])));
const accepted = new Counter('orders_accepted');
const RATE = Number(__ENV.RATE || 1000);

export const options = {
  scenarios: {
    ramp:   { executor: 'ramping-arrival-rate', startRate: 50, timeUnit: '1s',
              preAllocatedVUs: 200, maxVUs: 3000,
              stages: [{ target: RATE, duration: '60s' }] },
    steady: { executor: 'constant-arrival-rate', rate: RATE, timeUnit: '1s', duration: '120s',
              startTime: '60s', preAllocatedVUs: 500, maxVUs: 3000 },
  },
  thresholds: {
    'http_req_failed{scenario:steady}': ['rate<0.01'],
    'http_req_duration{scenario:steady}': ['p(99)<1000'],
    'http_reqs{scenario:steady}': ['count>0'],
  },
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
};

export default function () {
  const userId = 1_000_000 + exec.scenario.iterationInTest + (exec.scenario.name === 'steady' ? 50_000_000 : 0);
  const res = http.post(`${__ENV.BASE_URL}/api/seckill/${__ENV.EVENT_ID}/buy`, null, {
    headers: { Authorization: `Bearer ${signJwt(userId, __ENV.JWT_SECRET)}` },
  });
  check(res, { 'expected status': (r) => (LEGACY ? [200, 202] : [202, 409]).includes(r.status) });
  if (res.status === 202) accepted.add(1);
}
```

`contention.js`：`shared-iterations`，`vus: 1000`，`iterations: BUYERS`（默认 5000），活动库存 `STOCK`（默认 100）；用户 ID 取 `2_000_000 + exec.scenario.iterationInTest`；同样统计 `orders_accepted`，`teardown` 里不做断言（核对交给 `verify.sql`）。

`setup_event.py`（只用 Python 标准库，参照 `scripts/e2e_test.py` 的 `call()`）：管理员登录 → 创建商品（`totalStock` = 库存参数）→ 创建活动（`startTime` = 当前 − 2 s，`endTime` = 当前 + 1 h，库存由参数指定；注意 `createEvent` 要求活动库存 ≤ 商品总库存）→ 轮询直到活动状态为 `ACTIVE` → 打印 `EVENT_ID=<id>`。

`reset.sh`：`redis-cli FLUSHDB`；`mysql -e "DELETE FROM orders WHERE source_type='SECKILL'"`（P2 之后再加上 `TRUNCATE order_outbox`，用 `SHOW TABLES LIKE` 判断该表是否存在）。

`verify.sql`（基线版本没有 `seckill_event_id` 列，所以用 `ref_id`）：

```sql
SELECT COUNT(*) AS orders, COUNT(DISTINCT buyer_id) AS buyers
FROM orders WHERE source_type = 'SECKILL' AND ref_id = @event_id;
```

`summarize.py`：读取 `results/<config>/<scenario>-<n>.json`（k6 `--summary-export` 的输出），取 `http_reqs{scenario:steady}` 的速率，以及 `http_req_duration{scenario:steady}` 的 p50/p95/p99，三次运行取中位数，输出 Markdown 表。

`loadtest/README.md`：写明完整的执行顺序（启动服务 → `setup_event.py` → `k6 run --summary-export results/... -e ...` → 等待落库完成 → `verify.sql`），以及环境记录模板（CPU 型号/核数、内存、Docker 资源限制、k6 是否与服务同机、JDK、各镜像版本）。

### P0.5 基线测量

```bash
git worktree add ../fyp-baseline v0.4.0-baseline
# 在 ../fyp-baseline 启动旧版本（Java 17 目标版本，可用 JDK 21 运行），JWT_SECRET 与 k6 使用的值一致
k6 run -e BASE_URL=http://localhost:8080 -e EVENT_ID=$EVENT_ID -e JWT_SECRET=$JWT_SECRET -e LEGACY=1 -e RATE=1000 \
  --summary-export loadtest/results/A-baseline/throughput-1.json loadtest/throughput.js
```

- **agent**：只用 `RATE=50` 跑冒烟，验证脚本可用，结果放 `_smoke/`。
- **人**：在固定机器上按 `loadtest/README.md` 测正式基线：吞吐 3 次、争抢 3 次，结果放 `results/A-baseline/`，同时填写环境记录。

### P0.6 验收

- [ ] 空库启动：Flyway 执行 V1，`validate` 通过，应用正常启动。
- [ ] 用 v0.4.2 运行过的旧库启动：Flyway 把它基线化为 V1，`validate` 通过。
- [ ] `./mvnw -B verify` 通过（5 个测试）；`scripts/e2e_test.py` 8/8。
- [ ] 冒烟压测：`loadtest/results/_smoke/` 下有 throughput 和 contention 各一份，contention 的 `orders_accepted` 等于库存。

### 不在本阶段范围

升级 JDK/Boot；任何业务代码修改；修复 HANDOFF 里列出的已知问题。

---

## P1 · Java 21 + Spring Boot 3.5 + 虚拟线程

### P1.1 文件清单

| 操作 | 路径 | 改动 |
| --- | --- | --- |
| 修改 | `backend/pom.xml` | parent `3.3.5` → 最新 `3.5.x`；`java.version` 17 → 21；删除 `<lombok.version>` 覆盖（用 Boot 管理的版本）；`springdoc-openapi-starter-webmvc-ui` 2.6.0 → 最新 `2.8.x`（2.6 不兼容 Boot 3.5） |
| 修改 | `backend/Dockerfile` | 见下 |
| 修改 | `application.yml` | 虚拟线程、Hikari 连接池大小 |
| 修改 | README / HANDOFF | JDK 要求改为 21；删除“构建目标 Java 17”的说法；JDK 25 的警告改为“需 Lombok ≥ 1.18.40” |

动手前先确认 Spring Boot 3.5 是否仍在开源支持期内（spring.io/projects/spring-boot#support）。如果已经停止支持，暂停本阶段并在 PR 里说明，由人决定是否改走 Boot 4。

### P1.2 `application.yml`

```yaml
spring:
  threads:
    virtual:
      enabled: ${VIRTUAL_THREADS:true}
  datasource:
    hikari:
      maximum-pool-size: ${DB_POOL_SIZE:20}
```

### P1.3 `backend/Dockerfile`

```dockerfile
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -e dependency:go-offline
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
```

### P1.4 验收

- [ ] `./mvnw -B verify` 通过；`docker compose build backend` 成功。
- [ ] `scripts/e2e_test.py --users 60 --stock 20` 8/8。
- [ ] 用 `JAVA_OPTS="-Djdk.tracePinnedThreads=short"` 启动，跑 `scripts/e2e_test.py --users 200 --stock 50`，把日志中 pinning 堆栈的数量和来源贴进 PR。
- [ ] Swagger UI 可以打开。

### 不在本阶段范围

拆模块；Kafka；任何业务逻辑。

---

## P2 · Outbox + Kafka 异步落单

**目标**：秒杀请求在返回 202 之前，把购买意图持久化到 MySQL outbox；通过 Kafka 异步、幂等地落单；删除 Redis Stream 路径；顺带修复普通下单的竞态。

**部署前提**：上线这一版时，不能有处于 ACTIVE 状态的秒杀活动。Redis key 改了名，进行中的活动会读不到库存。

### P2.1 文件清单

| 操作 | 路径 | 说明 |
| --- | --- | --- |
| 新增 | `db/migration/V2__seckill_outbox.sql` | outbox 表、订单列与唯一键、活动的 `sold_count` / `reconciled` 列 |
| 修改 | `model/Order.java` | 加 `seckillEventId` |
| 修改 | `model/SeckillEvent.java` | 加 `soldCount`、`reconciled` |
| 修改 | `utils/RedisKeys.java` | key 加 hash tag；删除 stream 相关 key |
| 修改 | `resources/scripts/seckill_deduct.lua` | 只改注释中的 key 名 |
| 新增 | `resources/scripts/seckill_rollback.lua` | 补偿脚本 |
| 修改 | `config/RedisConfig.java` | 注册补偿脚本 bean |
| 新增 | `config/KafkaConfig.java` | topic、错误处理器 |
| 新增 | `service/SeckillEventCache.java` | 活动时间窗本地缓存 |
| 新增 | `repository/OutboxDao.java` | JdbcTemplate 读写 outbox |
| 修改 | `service/SeckillService.java` | 热路径、预热、编辑/删除时清缓存 |
| 新增 | `service/OutboxRelay.java` | outbox → Kafka |
| 新增 | `service/OutboxJanitor.java` | 清理已发送行 |
| 新增 | `service/SeckillOrderWriter.java` | 落单事务 |
| 新增 | `consumer/SeckillOrderListener.java` | Kafka 监听 |
| 删除 | `consumer/SeckillStreamConsumer.java` | 被 Kafka 取代 |
| 新增 | `service/SeckillReconciler.java` | 活动结束后对账 |
| 修改 | `dto/SeckillDtos.java` | 新增 `SeckillOrderMessage`；`SeckillBuyResponse.result` 增加 `UNAVAILABLE` |
| 修改 | `controller/SeckillController.java` | 状态码映射 |
| 修改 | `repository/SeckillEventRepository.java` | `incrementSold`、`findByStatusAndReconciledFalse` |
| 修改 | `repository/OrderRepository.java` | `countBySeckillEventId` |
| 修改 | `repository/ProductRepository.java`、`ItemRepository.java` | 条件更新 |
| 修改 | `service/OrderService.java` | 普通下单改用条件更新 |
| 修改 | `pom.xml` | `spring-kafka`、`caffeine`；`h2` 改为 test scope |
| 修改 | `application.yml` | Kafka 与 `app.seckill.*` 配置；**删除 h2 profile** |
| 修改 | `docker-compose.yml` | 加 kafka；redis 换 `redis:8` 并开 AOF |
| 修改 | `frontend/src/types/index.ts` | `SeckillBuyResponse.result` 加 `'UNAVAILABLE'` |
| 修改 | `scripts/e2e_test.py` | 第 7 步改为轮询等待 |
| 修改 | `loadtest/reset.sh`、`loadtest/verify.sql` | 加上 outbox；改用 `seckill_event_id` |
| 修改 | README / HANDOFF | 新链路说明；删掉 H2 快速启动；本地开发改为 `docker compose up -d mysql redis kafka` |

### P2.2 `V2__seckill_outbox.sql`

```sql
CREATE TABLE order_outbox (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  order_id    CHAR(36)    NOT NULL,
  user_id     BIGINT      NOT NULL,
  event_id    BIGINT      NOT NULL,
  payload     JSON        NOT NULL,
  status      TINYINT     NOT NULL DEFAULT 0,      -- 0 NEW, 1 SENT
  created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  sent_at     DATETIME(3) NULL,
  UNIQUE KEY uk_outbox_order_id (order_id),
  KEY idx_outbox_status_id (status, id),
  KEY idx_outbox_event_status (event_id, status)
);

ALTER TABLE orders
  ADD COLUMN seckill_event_id BIGINT NULL,
  ADD UNIQUE KEY uk_orders_buyer_seckill (buyer_id, seckill_event_id);

ALTER TABLE seckill_events
  ADD COLUMN sold_count INT NOT NULL DEFAULT 0,
  ADD COLUMN reconciled BIT(1) NOT NULL DEFAULT b'0';

-- 旧 key 名作废：未开始的活动重新预热
UPDATE seckill_events SET stock_warmed = b'0' WHERE status = 'PENDING';
```

`tracking_token` 上已经有唯一约束，它就是 `orderId`，不再新增列。

### P2.3 实体

```java
// Order.java
/** Set only for SECKILL orders; unique together with buyerId (one per user per event). */
private Long seckillEventId;

// SeckillEvent.java
@Column(nullable = false)
@Builder.Default
private Integer soldCount = 0;

/** True once SeckillReconciler has checked this ended event. */
@Column(nullable = false)
@Builder.Default
private boolean reconciled = false;
```

### P2.4 Redis

`RedisKeys`：

```java
public static String seckillStock(Long eventId)  { return "seckill:stock:{" + eventId + "}"; }
public static String seckillBought(Long eventId) { return "seckill:bought:{" + eventId + "}"; }
// 删除 seckillOrdersStream()、seckillConsumerGroup()
```

`scripts/seckill_rollback.lua`：

```lua
-- Return a slot after the outbox insert failed. Idempotent: only re-adds stock
-- if the user was actually in the bought set.
-- KEYS[1] = seckill:stock:{eventId}  KEYS[2] = seckill:bought:{eventId}  ARGV[1] = userId
if redis.call('SREM', KEYS[2], ARGV[1]) == 1 then
    redis.call('INCR', KEYS[1])
    return 1
end
return 0
```

`RedisConfig` 新增 `RedisScript<Long> seckillRollbackScript()` bean，写法同现有的 `seckillDeductScript`。注入时按参数名区分两个脚本，或加 `@Qualifier`。

### P2.5 `SeckillEventCache`

```java
@Component
public class SeckillEventCache {

    public record EventWindow(Long eventId, Long productId, BigDecimal price, Instant start, Instant end) {
        public boolean isActive(Instant now) { return !now.isBefore(start) && !now.isAfter(end); }
    }

    private final SeckillEventRepository repository;
    private final Cache<Long, Optional<EventWindow>> cache;

    public SeckillEventCache(SeckillEventRepository repository,
                             @Value("${app.seckill.event-cache-ttl:5s}") Duration ttl) {
        this.repository = repository;
        this.cache = Caffeine.newBuilder().expireAfterWrite(ttl).maximumSize(1_000).build();
    }

    /** Unknown ids are cached as empty too, so bad ids cannot hammer MySQL. */
    public Optional<EventWindow> get(Long eventId) {
        return cache.get(eventId, id -> repository.findById(id)
                .map(e -> new EventWindow(e.getId(), e.getProductId(), e.getSeckillPrice(),
                        e.getStartTime(), e.getEndTime())));
    }

    public void invalidate(Long eventId) { cache.invalidate(eventId); }
}
```

### P2.6 `OutboxDao`

```java
@Repository
public class OutboxDao {
    public record OutboxRow(long id, String orderId, long eventId, String payload) {}

    public void insert(String orderId, long userId, long eventId, String payloadJson) {
        jdbc.update("INSERT INTO order_outbox (order_id, user_id, event_id, payload) VALUES (?, ?, ?, ?)",
                orderId, userId, eventId, payloadJson);
    }

    /** Must be called inside a transaction; rows stay locked until commit. */
    public List<OutboxRow> lockBatch(int limit) {
        return jdbc.query("""
                SELECT id, order_id, event_id, payload FROM order_outbox
                WHERE status = 0 ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED""",
                (rs, i) -> new OutboxRow(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4)), limit);
    }

    public void markSent(List<Long> ids) {
        jdbc.batchUpdate("UPDATE order_outbox SET status = 1, sent_at = NOW(3) WHERE id = ?",
                ids.stream().map(id -> new Object[]{id}).toList());
    }

    public long countPending() { ... }                       // status = 0
    public long countPendingForEvent(long eventId) { ... }
    public int deleteSentBefore(Instant cutoff) { ... }      // status = 1 AND sent_at < ?
}
```

### P2.7 `SeckillDtos` 增改

```java
/** Kafka / outbox payload. orderId == Order.trackingToken. */
public record SeckillOrderMessage(String orderId, Long userId, Long eventId, Long productId,
                                  BigDecimal price, Instant acceptedAt) {}

public record SeckillBuyResponse(
        String result,        // ACCEPTED | SOLD_OUT | ALREADY_BOUGHT | NOT_ACTIVE | UNAVAILABLE
        String trackingToken,
        String message
) {}
```

### P2.8 `SeckillService` 改动

热路径（替换现有的 `buy` 和 `enqueueOrder`）：

```java
public SeckillBuyResponse buy(Long userId, Long eventId) {
    var window = eventCache.get(eventId).orElse(null);
    if (window == null || !window.isActive(Instant.now())) {
        return new SeckillBuyResponse("NOT_ACTIVE", null, "SecKill is not currently active.");
    }
    List<String> keys = List.of(RedisKeys.seckillStock(eventId), RedisKeys.seckillBought(eventId));
    Long result = redis.execute(seckillDeductScript, keys, String.valueOf(userId));
    long r = result == null ? -2 : result;
    if (r == 0)  return new SeckillBuyResponse("SOLD_OUT", null, "Sorry, this item is sold out.");
    if (r == -1) return new SeckillBuyResponse("ALREADY_BOUGHT", null, "You have already secured one. Limit: 1 per user.");
    if (r != 1)  return new SeckillBuyResponse("NOT_ACTIVE", null, "SecKill stock is not available yet.");

    String orderId = UUID.randomUUID().toString();
    var msg = new SeckillOrderMessage(orderId, userId, eventId, window.productId(), window.price(), Instant.now());
    try {
        outboxDao.insert(orderId, userId, eventId, objectMapper.writeValueAsString(msg));
    } catch (Exception e) {
        redis.execute(seckillRollbackScript, keys, String.valueOf(userId));
        log.error("[SecKill] outbox insert failed for event {} user {}; slot returned", eventId, userId, e);
        return new SeckillBuyResponse("UNAVAILABLE", null, "Service busy, please try again.");
    }
    return new SeckillBuyResponse("ACCEPTED", orderId, "Your order is being processed.");
}
```

其他改动：

- `warmStock(e)`：改为 `redis.opsForValue().setIfAbsent(stockKey, String.valueOf(e.getSeckillStock()))`。返回 `false` 时打一行 INFO 日志说明 key 已存在，但仍然把 `stockWarmed` 置为 true。
- `updateEvent`：保存之前先执行 `redis.delete(List.of(stockKey, boughtKey))`，保存之后 `eventCache.invalidate(id)`。活动只允许在 PENDING 状态下编辑，所以这时还没有买家。
- `deleteEvent`：在原有删除逻辑之后加 `eventCache.invalidate(id)`。
- 新增 `app.seckill.mode` 开关（`async` | `sync`，默认 `async`）的**读取逻辑留到 P3 实现**，本阶段不要加。

### P2.9 `SeckillController`

```java
@PostMapping("/{eventId}/buy")
public ResponseEntity<SeckillBuyResponse> buy(@AuthenticationPrincipal AuthPrincipal principal,
                                              @PathVariable Long eventId) {
    SeckillBuyResponse resp = seckillService.buy(principal.userId(), eventId);
    HttpStatus status = switch (resp.result()) {
        case "ACCEPTED" -> HttpStatus.ACCEPTED;
        case "UNAVAILABLE" -> HttpStatus.SERVICE_UNAVAILABLE;
        default -> HttpStatus.CONFLICT;          // SOLD_OUT, ALREADY_BOUGHT, NOT_ACTIVE
    };
    return ResponseEntity.status(status).body(resp);
}
```

前端 `SeckillCard.tsx` 不需要改：非 2xx 会进入 `catch`，那里已经在读 `err.response.data.message`。只需要更新 `types/index.ts`。

### P2.10 Relay 与清理

```java
@Component
public class OutboxRelay {

    @Scheduled(fixedDelayString = "${app.seckill.relay-interval-ms:100}")
    public void run() {
        // Drain quickly while there is a backlog, but yield to the scheduler eventually.
        for (int i = 0; i < 20 && publisher.publishBatch() == batchSize; i++) { }
    }
}

@Component
public class OutboxPublisher {

    /** Sends one locked batch; any send failure rolls back the whole batch (consumer is idempotent). */
    @Transactional
    public int publishBatch() {
        List<OutboxRow> rows = outboxDao.lockBatch(batchSize);
        if (rows.isEmpty()) return 0;
        List<CompletableFuture<SendResult<String, String>>> futures = rows.stream()
                .map(r -> kafka.send(topic, String.valueOf(r.eventId()), r.payload()))
                .toList();
        try {
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("Kafka send failed; batch will be retried", e);
        }
        outboxDao.markSent(rows.stream().map(OutboxRow::id).toList());
        return rows.size();
    }
}
```

- `OutboxPublisher` 必须是独立 bean，这样 `@Transactional` 才会经过代理生效。
- `OutboxRelay.run()` 要捕获 `publishBatch` 抛出的异常，打 WARN 日志后结束本轮，不要让异常冒泡出 `@Scheduled` 方法。
- `OutboxJanitor`：`@Scheduled(cron = "0 17 * * * *")`，调用 `deleteSentBefore(now - 3 天)`。

### P2.11 消费端

```java
@Component
public class SeckillOrderListener {

    @KafkaListener(topics = "${app.seckill.topic}", concurrency = "${app.seckill.consumer-concurrency:3}")
    public void onMessage(String payload) throws JsonProcessingException {
        SeckillOrderMessage m = objectMapper.readValue(payload, SeckillOrderMessage.class);
        try {
            writer.persist(m);
        } catch (DataIntegrityViolationException duplicate) {
            // tracking_token or (buyer_id, seckill_event_id) already exists: message was processed before.
            log.debug("[SecKill] duplicate order {}, skipped", m.orderId());
        }
    }
}

@Service
public class SeckillOrderWriter {

    /** Transaction lives here, not on the listener, so a duplicate-key rollback does not poison the caller. */
    @Transactional
    public void persist(SeckillOrderMessage m) {
        Order order = orderRepository.saveAndFlush(Order.builder()
                .buyerId(m.userId()).sourceType(Order.SourceType.SECKILL)
                .refId(m.eventId()).seckillEventId(m.eventId())
                .amount(m.price()).paymentMethod(SECKILL_PAYMENT)
                .trackingToken(m.orderId()).status(Order.Status.PENDING).build());
        if (eventRepository.incrementSold(m.eventId()) == 0) {
            throw new IllegalStateException("MySQL stock guard tripped for event " + m.eventId());
        }
        orderService.settle(order, SECKILL_PAYMENT);
    }
}
```

`SeckillEventRepository`：

```java
@Modifying
@Query("update SeckillEvent e set e.soldCount = e.soldCount + 1 where e.id = :id and e.soldCount < e.seckillStock")
int incrementSold(@Param("id") Long id);

List<SeckillEvent> findByStatusAndReconciledFalse(SeckillEvent.Status status);
```

`KafkaConfig`：

```java
@Bean NewTopic seckillOrdersTopic(...)    { return TopicBuilder.name(topic).partitions(partitions).replicas(replicas).build(); }
@Bean NewTopic seckillOrdersDltTopic(...) { return TopicBuilder.name(dltTopic).partitions(1).replicas(replicas).build(); }

@Bean
CommonErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template,
                                     @Value("${app.seckill.dlt-topic}") String dltTopic) {
    var recoverer = new DeadLetterPublishingRecoverer(template, (rec, ex) -> new TopicPartition(dltTopic, -1));
    var handler = new DefaultErrorHandler(recoverer, new FixedBackOff(1_000L, 3));
    handler.addNotRetryableExceptions(JsonProcessingException.class);
    return handler;
}
```

Boot 会自动把这个 `CommonErrorHandler` 装配到默认的 listener 容器工厂上。DLT 的分区写死成 `-1`，是为了不要求 DLT 和主 topic 的分区数一致。

### P2.12 对账 `SeckillReconciler`

`@Scheduled(fixedDelay = 60_000)`。对每个 `status=ENDED` 且 `reconciled=false` 的活动：若 `endTime` 已过去 2 分钟以上，且 `outboxDao.countPendingForEvent(id) == 0`，则计算下面四个数：

| 数 | 来源 |
| --- | --- |
| `redisSold` | `seckillStock - GET seckill:stock:{id}`（key 不存在时记为 null） |
| `soldCount` | `seckill_events.sold_count` |
| `orderCount` | `orderRepository.countBySeckillEventId(id)` |
| `pending` | 已确认为 0 |

三个数相等时打一行 INFO 日志；不相等时打 WARN 日志，并调用 `meterRegistry.counter("seckill.reconcile.mismatch").increment()`。最后把 `reconciled` 置为 true。`redisSold > orderCount` 表示有名额丢失（少卖），日志里要写明这一点。

### P2.13 普通下单竞态修复

```java
// ProductRepository
@Modifying
@Query("update Product p set p.totalStock = p.totalStock - 1 where p.id = :id and p.totalStock > 0")
int decrementStock(@Param("id") Long id);

// ItemRepository
@Modifying
@Query("update Item i set i.status = 'SOLD' where i.id = :id and i.status = 'ACTIVE'")
int markSold(@Param("id") Long id);
```

如果 JPQL 不接受枚举字符串字面量，改用参数传入 `Item.Status.SOLD` / `ACTIVE`。

`OrderService.buildProductOrder`：保留 `findById`（取价格、判断是否存在），删除 `setTotalStock` 和 `save(product)`，改为调用 `decrementStock(id)`；返回 0 时抛 `BusinessException("Product is out of stock.")`。

`buildItemOrder`：保留存在性和“不能买自己的”检查，删除 `setStatus` 和 `save(item)`，改为调用 `markSold(id)`；返回 0 时抛 `BusinessException("Item is no longer available.")`。

### P2.14 配置

`pom.xml`：加 `org.springframework.kafka:spring-kafka` 和 `com.github.ben-manes.caffeine:caffeine`；`com.h2database:h2` 的 scope 改成 `test`（`ReviewRepositoryTest` 还在用）。

`application.yml`：

```yaml
spring:
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP:localhost:9092}
    producer:
      acks: all
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.apache.kafka.common.serialization.StringSerializer
      properties:
        enable.idempotence: true
        max.in.flight.requests.per.connection: 5
        linger.ms: 5
        request.timeout.ms: 5000
        delivery.timeout.ms: 10000
        max.block.ms: 5000          # Kafka 不可用时 send() 最多阻塞 5 s，而不是默认的 60 s
    consumer:
      group-id: seckill-order-writer
      auto-offset-reset: earliest
      enable-auto-commit: false
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.apache.kafka.common.serialization.StringDeserializer
    listener:
      ack-mode: record

app:
  seckill:
    topic: seckill.orders
    dlt-topic: seckill.orders.DLT
    partitions: 6
    replicas: ${KAFKA_REPLICAS:1}
    consumer-concurrency: 3
    relay-interval-ms: 100
    relay-batch-size: 500
    event-cache-ttl: 5s
```

同时删除 `on-profile: h2` 整段。

`docker-compose.yml`：

```yaml
  redis:
    image: redis:8
    command: ["redis-server", "--appendonly", "yes"]
    # 其余不变

  kafka:
    image: apache/kafka:3.9.1     # 动手时取最新稳定版
    restart: unless-stopped
    environment:
      KAFKA_NODE_ID: 1
      KAFKA_PROCESS_ROLES: broker,controller
      KAFKA_LISTENERS: PLAINTEXT://:9092,CONTROLLER://:9093
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:9092
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: PLAINTEXT:PLAINTEXT,CONTROLLER:PLAINTEXT
      KAFKA_CONTROLLER_QUORUM_VOTERS: 1@kafka:9093
      KAFKA_CONTROLLER_LISTENER_NAMES: CONTROLLER
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_MIN_ISR: 1
      KAFKA_LOG_DIRS: /var/lib/kafka/data
    volumes:
      - kafka_data:/var/lib/kafka/data
    healthcheck:
      test: ["CMD-SHELL", "/opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server localhost:9092 > /dev/null"]
      interval: 10s
      timeout: 10s
      retries: 10

  backend:
    depends_on:
      kafka: { condition: service_healthy }   # 与现有的 mysql、redis 并列
    environment:
      KAFKA_BOOTSTRAP: kafka:9092
```

在顶层 `volumes:` 里加上 `kafka_data:`。如果需要在宿主机上用 k6 / 本地 IDE 直连 Kafka，另加一个 `EXTERNAL` listener 映射到 `localhost:29092`，并在 README 里写明。

`scripts/e2e_test.py` 第 7 步：把固定的 `time.sleep(3)` 改成最多 20 秒的轮询，直到赢家能查到 SECKILL 订单为止。

### P2.15 验收（命令和输出都要贴进 PR）

- [ ] `./mvnw -B verify`；`npm run build`。
- [ ] `scripts/e2e_test.py --users 200 --stock 50` 8/8，之后：`SELECT COUNT(*), COUNT(DISTINCT buyer_id), (SELECT sold_count FROM seckill_events WHERE id=?) FROM orders WHERE seckill_event_id=?` 三个数都是 50；`redis-cli GET 'seckill:stock:{<id>}'` 是 0。
- [ ] 抢购进行中执行 `docker compose kill backend` 再 `up -d backend`：订单数 = 已接受数。
- [ ] 执行 `docker compose pause kafka` 30 秒，期间下单仍然返回 202，`order_outbox` 里 `status=0` 的行数在增长；`unpause` 之后积压被清零，订单全部落库。
- [ ] 停掉 backend，执行 `kafka-consumer-groups.sh --bootstrap-server kafka:9092 --group seckill-order-writer --reset-offsets --to-earliest --all-topics --execute`，再启动 backend：订单数不变，日志中出现 duplicate skipped。
- [ ] 两个用户并发购买同一个库存为 1 的普通商品：只有一个成功。
- [ ] 活动结束 2 分钟后，对账日志显示三个数一致。

### 不在本阶段范围

Testcontainers 自动化测试（P6a）；ShedLock / 多副本（P6b）；指标 gauge（P6b）；sync 模式（P3）。

---

## P3 · 三配置压测

### P3.1 改动

| 操作 | 路径 | 说明 |
| --- | --- | --- |
| 修改 | `service/SeckillService.java` | `app.seckill.mode=sync` 时，Lua 成功后直接调用 `SeckillOrderWriter.persist(msg)`（捕获重复键异常），不写 outbox |
| 修改 | `application.yml` | `app.seckill.mode: ${SECKILL_MODE:async}`，注释写明“sync 仅用于压测对照” |
| 新增 | `loadtest/results/{A-baseline,B-sync,C-outbox-kafka}/` | 正式结果 |
| 新增 | `loadtest/results/matrix/` | 连接池 {10, 20, 40} × 虚拟线程 {开, 关}，只跑吞吐场景 |
| 修改 | `README.md` | 新增 “Performance” 一节：三配置对比表、矩阵表、环境记录、设计取舍（为什么热路径保留一次 MySQL 插入、什么情况下会少卖、怎么对账） |
| 修改 | `docs/UPGRADE_PLAN.md` | “数据记录表” 填入实测值 |

### P3.2 执行规则

- 正式结果由**人**在固定机器上跑；agent 负责 sync 开关、`summarize.py` 和 README 表格模板。
- 每次运行之前先执行 `reset.sh` 并新建活动。吞吐场景的活动库存设为 1,000,000，对应商品的 `totalStock` 也要设为 1,000,000。
- `RATE` 从 1000 开始，按 500 一档往上加，直到稳定阶段 p99 超过 1 s 或错误率超过 1%。报告其中最高的、仍然达标的那一档。
- 每档跑完后，等 outbox 积压和 consumer lag 都归零，再执行 `verify.sql`，结果写进同目录的 `verify-<n>.txt`。

### P3.3 验收

- [ ] 三个配置目录下各有吞吐 3 次、争抢 3 次的 JSON 和 verify 文件。
- [ ] 争抢场景：订单数 = 库存，`COUNT(DISTINCT buyer_id)` = 订单数。
- [ ] README 的表格由 `summarize.py` 生成，不手填。

---

## P4a · 拆 Maven 多模块（不改行为）

### P4a.1 目标结构

```text
FYP/
├── pom.xml                  # 新增：聚合 + 继承 spring-boot-starter-parent
├── mvnw  mvnw.cmd  .mvn/    # 从 backend/ 移来
├── catalog-core/            # 新增：普通 jar，不打 fat jar
│   └── src/main/java/my/edu/ukm/ftsm/ecommerce/
│       ├── model/        Product, Item, SeckillEvent, Review
│       ├── repository/   ProductRepository, ItemRepository, SeckillEventRepository, ReviewRepository
│       └── utils/        RedisKeys
└── backend/                 # 其余代码不动，包名不变
```

包名不变，所以 backend 的 `@SpringBootApplication` 仍能扫描到 catalog-core 里的实体和仓库，无需加 `@EntityScan`。

### P4a.2 根 `pom.xml`

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version><!-- 与 P1 一致 --></version>
    <relativePath/>
</parent>
<groupId>my.edu.ukm.ftsm</groupId>
<artifactId>ftsm-parent</artifactId>
<version>0.0.1-SNAPSHOT</version>
<packaging>pom</packaging>
<modules>
    <module>catalog-core</module>
    <module>backend</module>
</modules>
<properties>
    <java.version>21</java.version>
</properties>
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>my.edu.ukm.ftsm</groupId>
            <artifactId>catalog-core</artifactId>
            <version>${project.version}</version>
        </dependency>
    </dependencies>
</dependencyManagement>
```

- `backend/pom.xml`：parent 改为 `ftsm-parent`（`relativePath ../pom.xml`），加 `catalog-core` 依赖，其余保留。
- `catalog-core/pom.xml`：依赖 `spring-boot-starter-data-jpa`、`spring-boot-starter-data-redis`、`spring-boot-starter-validation`、`lombok`；配置 Lombok 注解处理器；**不要**声明 `spring-boot-maven-plugin`（否则会被打成不可依赖的 fat jar）。

### P4a.3 其他改动

| 路径 | 改动 |
| --- | --- |
| `backend/Dockerfile` | 构建上下文改为仓库根目录：先 `COPY pom.xml`、`catalog-core/`、`backend/`，再 `mvn -B -q -pl backend -am -DskipTests package`；建议用 `RUN --mount=type=cache,target=/root/.m2` 缓存依赖 |
| `docker-compose.yml` | `backend.build` 改为 `{ context: ., dockerfile: backend/Dockerfile }` |
| 新增 `.dockerignore`（根目录） | 排除 `**/target`、`frontend/node_modules`、`.git`、`loadtest/results` |
| README / HANDOFF | 构建命令改为在根目录执行 `./mvnw -pl backend -am spring-boot:run`；更新项目结构 |

测试仍然放在 `backend/src/test`（`ReviewRepositoryTest` 测的是 catalog-core 里的仓库，但需要 backend 的启动类）。

### P4a.4 验收

- [ ] 根目录 `./mvnw -B verify` 通过，测试数量与拆分前一致。
- [ ] `docker compose build backend && docker compose up -d`，`scripts/e2e_test.py` 8/8。
- [ ] `git diff --stat -M` 中，移动的文件显示为 rename（内容未改）。

---

## P4b · MCP server + Spring AI 助手 + SSE + Resilience4j

动手前先确认 Spring AI 1.1.x 与 P1 选定的 Boot 版本兼容，并确认所用 DeepSeek 模型支持 tool calling。任一项不满足，暂停并在 PR 里说明。

### P4b.1 文件清单

| 操作 | 路径 | 说明 |
| --- | --- | --- |
| 修改 | 根 `pom.xml` | `<module>mcp-server</module>`；dependencyManagement 导入 `org.springframework.ai:spring-ai-bom` |
| 新增 | `catalog-core/.../search/CatalogSearchService.java` | 本阶段只做关键词检索（现有 LIKE 逻辑），P5 再替换 |
| 新增 | `catalog-core/.../search/SearchDtos.java` | `ProductView`、`ItemView`、`StockView`、`FlashSaleView` |
| 修改 | `catalog-core/.../repository/SeckillEventRepository.java` | `findByProductIdAndStatus` |
| 新增 | `mcp-server/pom.xml`、`Dockerfile`、`src/main/resources/application.yml` | |
| 新增 | `mcp-server/.../mcp/McpServerApplication.java`、`ShopTools.java`、`ToolsConfig.java` | |
| 新增 | `mysql/init/01-readonly-user.sh` | 只读账号 |
| 修改 | `backend/pom.xml` | Spring AI、MCP client、Resilience4j 依赖 |
| 新增 | `backend/.../service/AssistantService.java` | 取代 `ChatService` |
| 新增 | `backend/.../service/OrderTools.java` | 本地 `@Tool`，用户 ID 来自 ToolContext |
| 新增 | `backend/.../service/ToolCallRecorder.java` | 记录工具调用，供评测使用 |
| 新增 | `backend/.../config/AssistantConfig.java` | ChatClient、ChatMemory |
| 新增 | `backend/.../controller/AssistantController.java` | `POST /api/assistant/stream` |
| 修改 | `backend/.../controller/ChatbotController.java` | 内部改为调用 `AssistantService` 并聚合结果 |
| 删除 | `backend/.../service/ChatService.java`、`config/WebClientConfig.java` | |
| 修改 | `backend/.../config/SecurityConfig.java` | 放行 ASYNC 分派 |
| 修改 | `dto/ChatDtos.java` | `ChatRequest` 加可选的 `conversationId` |
| 修改 | `application.yml`（backend） | Spring AI、MCP client、Resilience4j 配置；删除 `app.llm.*` |
| 修改 | `frontend/nginx.conf` | SSE location |
| 修改 | `frontend/src/services/api.ts`、`features/chatbot/ChatWidget.tsx`、`types/index.ts` | 流式渲染 |
| 修改 | `docker-compose.yml`、`.env.example` | mcp-server 服务与环境变量 |
| 新增 | `eval/assistant_questions.jsonl`、`eval/run_assistant_eval.py` | |

### P4b.2 MCP server

`mcp-server/pom.xml` 依赖：`catalog-core`、`spring-boot-starter-web`、`spring-ai-starter-mcp-server-webmvc`、`spring-boot-starter-data-jpa`、`spring-boot-starter-data-redis`、`spring-boot-starter-actuator`、`mysql-connector-j`(runtime)；声明 `spring-boot-maven-plugin`。

```java
@SpringBootApplication(scanBasePackages = {"my.edu.ukm.ftsm.ecommerce.mcp", "my.edu.ukm.ftsm.ecommerce.search"})
@EntityScan("my.edu.ukm.ftsm.ecommerce.model")
@EnableJpaRepositories("my.edu.ukm.ftsm.ecommerce.repository")
public class McpServerApplication { ... }

@Configuration
class ToolsConfig {
    @Bean
    ToolCallbackProvider shopTools(ShopTools tools) {
        return MethodToolCallbackProvider.builder().toolObjects(tools).build();
    }
}
```

`ShopTools`（`@Component`，全部只读；description 用英文写，并说明“什么时候用”）：

| 方法 / 工具名 | 参数 | 实现 |
| --- | --- | --- |
| `search_products` | `keyword`；`maxPrice`（可选）；`category`（可选） | `CatalogSearchService.searchProducts`，最多返回 10 条 `ProductView` |
| `search_secondhand_items` | `keyword`；`maxPrice`（可选） | `CatalogSearchService.searchItems`，只返回 ACTIVE 状态 |
| `get_product_detail` | `productId` | 商品信息 + `ReviewRepository` 算出的平均分和评价数 |
| `get_stock` | `productId` | `totalStock`；若该商品有 ACTIVE 状态的秒杀，附上 `{eventId, seckillPrice, remaining = GET seckill:stock:{eventId}, endsAt}` |
| `list_flash_sales` | `status`（可选：PENDING / ACTIVE，默认两者） | 活动列表 + 商品名 |

```java
@Tool(name = "get_stock",
      description = "Get real-time stock for an official store product, including remaining flash-sale (SecKill) units if a sale is live. Use when the user asks whether something is in stock or how many are left.")
public StockView getStock(@ToolParam(description = "Product id from search_products") long productId) { ... }
```

`mcp-server/src/main/resources/application.yml`：

```yaml
server:
  port: 8081
spring:
  application:
    name: ftsm-mcp-server
  datasource:
    url: ${DB_URL:jdbc:mysql://localhost:3306/ftsm_ecommerce?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC}
    username: ${MCP_DB_USERNAME:ftsm_ro}
    password: ${MCP_DB_PASSWORD:}
  jpa:
    hibernate:
      ddl-auto: none
    open-in-view: false
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
  ai:
    mcp:
      server:
        name: ftsm-shop
        version: 1.0.0
        type: SYNC
        protocol: STREAMABLE      # 以 Spring AI 1.1 文档中 streamable HTTP 的配置项为准
management:
  endpoints:
    web:
      exposure:
        include: health
```

`mysql/init/01-readonly-user.sh`（compose 中挂载到 mysql 的 `/docker-entrypoint-initdb.d/`，只在数据卷为空时执行；已有数据卷的情况要在 README 里给出手动执行的命令）：

```bash
#!/bin/bash
mysql -uroot -p"$MYSQL_ROOT_PASSWORD" <<SQL
CREATE USER IF NOT EXISTS 'ftsm_ro'@'%' IDENTIFIED BY '${MCP_DB_PASSWORD}';
GRANT SELECT ON \`${MYSQL_DATABASE}\`.* TO 'ftsm_ro'@'%';
SQL
```

compose 中给 mysql 服务加环境变量 `MCP_DB_PASSWORD`；新增 `mcp-server` 服务（构建上下文为根目录，`dockerfile: mcp-server/Dockerfile`，端口 8081，`depends_on` mysql 和 redis 健康，并配置基于 actuator health 的 healthcheck；先确认运行镜像里有 `curl` 或 `wget`，没有就在 Dockerfile 里安装，或者改用 `bash -c '</dev/tcp/localhost/8081'` 做 TCP 检查）。

### P4b.3 backend：助手

依赖（版本由 BOM 管理）：`spring-ai-starter-model-deepseek`、`spring-ai-starter-mcp-client`、`io.github.resilience4j:resilience4j-spring-boot3`、`io.github.resilience4j:resilience4j-reactor`、`spring-boot-starter-aop`。保留 `spring-boot-starter-webflux`，Spring AI 的流式调用需要它。

`application.yml`（删除 `app.llm.*`）：

```yaml
spring:
  ai:
    model:
      chat: ${LLM_PROVIDER:deepseek}        # 设为 none 可在没有 key 时启动
    deepseek:
      api-key: ${LLM_API_KEY:}
      base-url: ${DEEPSEEK_BASE_URL:https://api.deepseek.com}   # 注意：不带 /v1
      chat:
        options:
          model: ${LLM_MODEL:deepseek-v4-flash}
          temperature: 0.3
    mcp:
      client:
        enabled: ${MCP_CLIENT_ENABLED:true}
        type: SYNC
        request-timeout: 20s
        streamable-http:
          connections:
            shop:
              url: ${MCP_SERVER_URL:http://localhost:8081}
  mvc:
    async:
      request-timeout: 60s

resilience4j:
  ratelimiter:
    instances:
      llm: { limit-for-period: 20, limit-refresh-period: 1s, timeout-duration: 0 }
  circuitbreaker:
    instances:
      llm: { sliding-window-size: 20, minimum-number-of-calls: 10, failure-rate-threshold: 50, wait-duration-in-open-state: 30s }
```

`.env.example`：删除 `LLM_BASE_URL`；新增 `LLM_PROVIDER`、`DEEPSEEK_BASE_URL`、`MCP_SERVER_URL`、`MCP_DB_PASSWORD`。

**硬性要求**：mcp-server 不可用时，backend 仍然必须能启动，助手返回降级提示。实现时先验证 MCP client 在启动时连不上 server 的行为；如果会导致启动失败，就改为延迟初始化，或者在 `AssistantService` 里通过 `ObjectProvider<ToolCallbackProvider>` 获取工具。用 `docker compose stop mcp-server && docker compose restart backend` 验证。

`AssistantConfig`：

```java
@Bean
ChatClient assistantChatClient(ChatClient.Builder builder, ChatMemory chatMemory) {
    return builder
            .defaultSystem("""
                You are the FTSM Marketplace Assistant for UKM students. Answer only from tool results;
                if a tool returns nothing, say you could not find it. Prices are in RM. Be concise.
                """)
            .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
            .build();
}
```

`ChatMemory` 用 Spring AI 自动配置的 `MessageWindowChatMemory`（内存存储），窗口大小 10。

`OrderTools`：

```java
@Component
public class OrderTools {
    @Tool(name = "my_orders", description = "List the signed-in user's 10 most recent orders with status and amount. Use for questions about 'my order', delivery or payment status.")
    public List<OrderDtos.OrderResponse> myOrders(ToolContext ctx) {
        Long userId = (Long) ctx.getContext().get("userId");
        return orderService.listForBuyer(userId).stream().limit(10).toList();
    }
}
```

`AssistantService`：

```java
@RateLimiter(name = "llm")
@CircuitBreaker(name = "llm", fallbackMethod = "fallback")
public Flux<String> stream(Long userId, String conversationId, String message) {
    String convId = userId + ":" + conversationId;
    return chatClient.prompt()
            .user(message)
            .toolCallbacks(recorder.wrap(mcpTools.getToolCallbacks()))
            .tools(orderTools)
            .toolContext(Map.of("userId", userId, "conversationId", convId))
            .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, convId))
            .stream()
            .content()
            .timeout(Duration.ofSeconds(30));
}

private Flux<String> fallback(Long userId, String conversationId, String message, Throwable t) {
    log.warn("[Assistant] fallback: {}", t.toString());
    return Flux.just("The assistant is temporarily unavailable. Please try again later.");
}
```

`LLM_PROVIDER=none` 时容器里没有 `ChatClient.Builder` bean，用 `ObjectProvider` 判断，直接返回 “AI assistant is not configured yet.”（与现在的行为一致）。

`ToolCallRecorder`：`wrap(ToolCallback[])` 把每个回调包一层委托，在 `call(input, toolContext)` 里记录 `(conversationId, toolName, 时间)`，存进一个按会话分组的 LRU（最多 1000 个会话）。另外提供 `@Profile("dev")` 的 `GET /api/assistant/debug/tool-calls?conversationId=`，供评测脚本读取，只在 dev profile 下注册。

`AssistantController`：

```java
@PostMapping(value = "/api/assistant/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<ServerSentEvent<String>> stream(@AuthenticationPrincipal AuthPrincipal principal,
                                            @Valid @RequestBody ChatRequest req) {
    Long userId = principal.userId();                          // 进入 Reactor 线程之前取出
    String conv = req.conversationId() == null ? "default" : req.conversationId();
    return assistantService.stream(userId, conv, req.message())
            .map(chunk -> ServerSentEvent.builder(chunk).event("token").build())
            .concatWithValues(ServerSentEvent.builder("").event("done").build())
            .onErrorResume(e -> Flux.just(ServerSentEvent.builder("Something went wrong.").event("error").build()));
}
```

`ChatbotController`（保留 `POST /api/chat`，返回值不变）：调用 `assistantService.stream(...).collectList().map(String::join)` 后 `block(Duration.ofSeconds(60))`，用户 ID 从 `@AuthenticationPrincipal` 取。

`SecurityConfig`：在 `authorizeHttpRequests` 的**第一条**加上

```java
.dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
```

原因：`JwtAuthFilter` 继承自 `OncePerRequestFilter`，默认不处理 ASYNC 分派，而且它也没有把认证信息存进 `SecurityContextRepository`。所以当 controller 返回 `Mono` 或 `Flux` 时，异步分派阶段拿不到认证信息，被 `anyRequest().authenticated()` 拦成 403。这正是 v0.4.0 把 `ChatbotController` 改成阻塞调用的真正原因。初始的 REQUEST 分派已经完成鉴权，所以放行 ASYNC 分派是安全的。

### P4b.4 前端

`api.ts`：

```typescript
export async function streamAssistant(
  message: string, conversationId: string,
  onToken: (t: string) => void, signal?: AbortSignal,
): Promise<void> {
  const token = useAuthStore.getState().token;
  const res = await fetch(`${import.meta.env.VITE_API_BASE_URL || '/api'}/assistant/stream`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream',
               ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: JSON.stringify({ message, conversationId }),
    signal,
  });
  if (res.status === 401) { useAuthStore.getState().logout(); window.location.href = '/login'; return; }
  if (!res.ok || !res.body) throw new Error(`HTTP ${res.status}`);
  // 按 SSE 规范解析：事件以空行分隔；同一事件内多行 data: 用 '\n' 拼接；按 event 名分发 token / done / error
}
```

`ChatWidget.tsx`：

- 发送时先追加一个空的 assistant 气泡，收到 `token` 事件就把文本追加到这个气泡。
- `conversationId` 用 `crypto.randomUUID()` 生成，存在组件的 state 里（刷新页面即开始新会话）。
- 收到 `error` 事件时显示错误文本。
- 组件卸载时 `abort()` 正在进行的请求。

`nginx.conf`（放在 `location /api/` 之前）：

```nginx
location /api/assistant/stream {
    proxy_pass http://backend:8080/api/assistant/stream;
    proxy_http_version 1.1;
    proxy_set_header Connection "";
    proxy_set_header Host $host;
    proxy_buffering off;
    proxy_cache off;
    proxy_read_timeout 300s;
}
```

### P4b.5 评测

`eval/assistant_questions.jsonl`：25 条，格式 `{"q": "...", "expected_tools": ["get_stock"], "lang": "en|ms|zh"}`。覆盖每个工具至少 3 条、多工具组合的 3 条，以及不应调用任何工具的闲聊 3 条（`expected_tools: []`）。

`eval/run_assistant_eval.py`：

- 用 `--token` 传入一个测试账号的 JWT。
- 对每个问题生成新的 `conversationId`，调用流式接口，记录首个 `token` 事件到达的时间（TTFT）和总耗时。
- 再调用 dev 的 debug 接口取回实际调用的工具。
- 统计：工具集合完全匹配的比例、至少命中一个期望工具的比例、TTFT 的 p50/p95。
- 结果输出到 `eval/results/assistant-<date>.md`。

### P4b.6 验收

- [ ] `npx @modelcontextprotocol/inspector` 连上 `http://localhost:8081`（具体传输方式以 Spring AI 文档为准），能列出 5 个工具并逐个调用成功。**人**截图放进 `docs/images/mcp-inspector.png`。
- [ ] 问“FTSM Hoodie 还有货吗”：debug 接口显示调用了 `get_stock`，回答中的数字与数据库一致。
- [ ] 用户 A 的会话无法查到用户 B 的订单（模型的参数里根本没有 userId；写一个单元测试，直接调用 `OrderTools`，验证它只会读取 ToolContext 里的 userId）。
- [ ] `LLM_API_KEY=bad` 连续请求 15 次：`/actuator/circuitbreakers` 中的 `llm` 进入 OPEN，接口立即返回降级文本。（actuator 暴露的端点里要加上 `circuitbreakers`。）
- [ ] mcp-server 停止时，backend 正常启动，助手返回降级文本或无工具的回答。
- [ ] 通过 nginx（`http://localhost/`）访问时，回答是逐段出现的，而不是一次性出现。
- [ ] `run_assistant_eval.py` 的结果文件已提交。

### 不在本阶段范围

向量检索；`/api/search`；把 Marketplace 搜索框改到新接口。

---

## P5a · 演示目录、FULLTEXT、`/api/search`、评测框架

### P5a.1 文件清单

| 操作 | 路径 | 说明 |
| --- | --- | --- |
| 新增 | `data/catalog.json` | 400 个 B2C 商品 + 200 个 C2C 物品，见下 |
| 新增 | `backend/.../config/DemoCatalogSeeder.java` | `@Profile("demo")` 导入 |
| 新增 | `db/migration/V3__catalog_fulltext.sql` | FULLTEXT 索引 |
| 修改 | `catalog-core/.../search/CatalogSearchService.java` | 关键词召回改用 FULLTEXT |
| 新增 | `catalog-core/.../search/HybridSearchService.java` | 本阶段只实现 `keyword` 模式，接口按四种模式设计 |
| 新增 | `backend/.../controller/SearchController.java` | `GET /api/search` |
| 修改 | `dto/` 新增 `SearchDtos.java`（backend） | `SearchHit`、`SearchResponse` |
| 修改 | `SecurityConfig` | 放行 `GET /api/search` |
| 修改 | `frontend/.../marketplace/Marketplace.tsx`、`api.ts`、`types/index.ts` | 有关键词时改用 `/api/search` |
| 新增 | `frontend/.../marketplace/SearchResults.tsx` | 混合结果列表 |
| 新增 | `eval/queries.jsonl` | 80 条查询，`relevant` 字段**留空** |
| 新增 | `eval/run_retrieval_eval.py` | |
| 新增 | `eval/README.md` | 标注规则 |

### P5a.2 目录数据

`data/catalog.json`：

```json
{
  "products": [
    {"id": 1001, "name": "...", "description": "...", "category": "Electronics",
     "price": 129.00, "totalStock": 50, "lang": "en"}
  ],
  "items": [
    {"id": 5001, "sellerKey": "seller03", "title": "...", "description": "...",
     "category": "Books", "condition": "LIKE_NEW", "price": 25.00, "lang": "ms"}
  ]
}
```

- 用固定的 ID（商品 1001 起，物品 5001 起），评测集通过 ID 引用；数据库自增 ID 不稳定。
- 类目约 10 个：Electronics、Stationery、Books、Apparel、Dorm、Sports、Food、Accessories、Lifestyle、Services。商品的标题和描述按英语 / 马来语 / 中文 6:2:2 的比例分配，并且要有意识地制造同义词，例如 “running shoes / kasut lari / 跑鞋”、“earbuds / earphone / 耳机”。
- 价格按类目给出合理范围；C2C 物品的 `sellerKey` 对应 20 个演示卖家。
- `DemoCatalogSeeder`：
  - 创建 20 个已验证的卖家账号 `sellerNN@siswa.ukm.edu.my`，密码取 `SEED_DEMO_PASSWORD`。
  - 用 JdbcTemplate 以显式 ID 执行 `INSERT IGNORE` 导入商品和物品（MySQL 允许向自增列写入显式值）。
  - 导入完成后执行 `ALTER TABLE ... AUTO_INCREMENT` 确保后续自增 ID 大于 10000。

### P5a.3 `V3__catalog_fulltext.sql`

```sql
ALTER TABLE products ADD FULLTEXT INDEX ft_products (name, description, category) WITH PARSER ngram;
ALTER TABLE items    ADD FULLTEXT INDEX ft_items (title, description, category) WITH PARSER ngram;
```

### P5a.4 检索接口

`CatalogSearchService`：

```java
// 原生 SQL，商品与物品各取 top 20，按 MATCH 分数合并，返回前 limit 个 "product:<id>" / "item:<id>"
SELECT id, MATCH(name, description, category) AGAINST (:q IN NATURAL LANGUAGE MODE) AS score
FROM products
WHERE MATCH(name, description, category) AGAINST (:q IN NATURAL LANGUAGE MODE)
  AND (:maxPrice IS NULL OR price <= :maxPrice)
  AND (:category IS NULL OR category = :category)
ORDER BY score DESC LIMIT 20
```

物品额外加上 `status = 'ACTIVE'` 条件。

`HybridSearchService`：

```java
public enum Mode { KEYWORD, VECTOR, RRF, RRF_RERANK }

public record Ranked(String ref, Map<String, Integer> ranks) {}   // ranks: {"keyword": 3, "vector": 1}

public SearchResult search(String q, Filters f, Mode mode, int topK);   // SearchResult 带 usage（token 数）
```

本阶段只有 `KEYWORD` 模式可用，其余模式抛 `BusinessException("mode not available yet")`。

`SearchController`：`GET /api/search?q=&maxPrice=&category=&mode=&debug=`。

- `mode` 和 `debug` 只在 `dev` / `test` profile 下生效，其他 profile 下忽略，固定使用默认模式。默认模式在 P5a 是 `KEYWORD`，P5b 改为 `RRF_RERANK`。
- 响应：`{ hits: [{type, id, title, price, category, imageUrl}], debug?: {ranks: [...], usage: {embeddingTokens, rerankInputTokens, rerankOutputTokens}, latencyMs} }`。

前端：`Marketplace.tsx` 在搜索框有内容时调用 `searchApi.search(q)`，并渲染 `SearchResults`；每条结果按 `type` 链接到 `/product/:id` 或 `/item/:id`。没有关键词时保持原有的浏览逻辑。

### P5a.5 评测查询（agent 只写查询，不标注）

`eval/queries.jsonl` 每行：`{"id": "q01", "q": "...", "type": "lexical|synonym|crosslingual|constraint", "relevant": []}`。四类各 20 条。

`eval/README.md` 写明标注规则：

- 每条标注 1–3 个 `product:<id>` / `item:<id>`。
- 只依据 `data/catalog.json` 的内容判断相关性，**不要看任何检索结果**。
- 标注完成后单独提交，提交信息为 `eval: label queries`。

`eval/run_retrieval_eval.py`：

- 参数：`--base`、`--modes keyword,vector,rrf,rrf_rerank`、`--price-file eval/pricing.json`（单价由人在跑分当天填写）。
- 调用 `/api/search?debug=true`，计算 Recall@5（定义为标注项出现在前 5 名中的比例，对全部查询取平均）、MRR、p50 延迟、每千次查询的费用。
- 输出总表，并按 `type` 分组；遇到 `relevant` 为空的查询时直接报错退出。

### P5a.6 验收

- [ ] `SPRING_PROFILES_ACTIVE=dev,demo` 启动后，商品数 ≥ 400、物品数 ≥ 200。
- [ ] `GET /api/search?q=hoodie` 返回结果；中文查询（如“耳机”）能召回中文商品。
- [ ] 前端搜索框可用。
- [ ] 用一份临时标注过的副本跑通 `run_retrieval_eval.py --modes keyword`。**不要提交**这份临时标注和它的结果。

---

## 人工步骤 · 标注评测集

人按 `eval/README.md` 填写 `relevant` 字段，单独提交到 `main`（或 P5b 开工前的基线分支）。P5b 的 agent 开始之前，要先确认 `git log -- eval/queries.jsonl` 里这次提交早于任何向量检索代码。

---

## P5b · 向量检索、RRF、LLM 重排、跑分

### P5b.1 Embedding 提供方（二选一，由人在提示词里指定）

| 方案 | 依赖 | 配置 |
| --- | --- | --- |
| OpenAI | `spring-ai-starter-model-openai` | `spring.ai.model.embedding=openai`，`spring.ai.model.chat=${LLM_PROVIDER:deepseek}`（防止 OpenAI 的 chat 被自动配置），`spring.ai.openai.api-key=${OPENAI_API_KEY}`，`spring.ai.openai.embedding.options.model=text-embedding-3-small` |
| Ollama 本地 | `spring-ai-starter-model-ollama` | `spring.ai.model.embedding=ollama`，`spring.ai.ollama.base-url=${OLLAMA_BASE_URL:http://localhost:11434}`，`spring.ai.ollama.embedding.options.model=bge-m3`；compose 新增 `ollama` 服务，并在启动时 `ollama pull bge-m3` |

依赖加在 `catalog-core` 上，配置写在 backend 和 mcp-server 各自的 `application.yml` 里。

### P5b.2 文件清单

| 操作 | 路径 | 说明 |
| --- | --- | --- |
| 修改 | `catalog-core/pom.xml` | `spring-ai-starter-vector-store-redis`、所选 embedding starter |
| 新增 | `catalog-core/.../search/VectorStoreConfig.java` | 手动构建 `RedisVectorStore`，以声明元数据字段 |
| 新增 | `catalog-core/.../search/CatalogIndexer.java` | 文档构建、upsert、删除、全量回填 |
| 新增 | `catalog-core/.../search/CatalogChangedEvent.java` | |
| 修改 | `catalog-core/.../search/HybridSearchService.java` | VECTOR、RRF 模式；RRF_RERANK 通过 `Reranker` 接口实现 |
| 新增 | `catalog-core/.../search/Reranker.java` | 接口；catalog-core 不依赖 chat 模型 |
| 新增 | `backend/.../service/LlmReranker.java` | 用 ChatClient 实现 |
| 修改 | `ProductService`、`ItemService`、`OrderService`（物品售出时） | 发布 `CatalogChangedEvent` |
| 新增 | `backend/.../config/CatalogReindexRunner.java` | `app.catalog.reindex=true` 时执行全量回填 |
| 修改 | `mcp-server` 的 `ShopTools.searchProducts` / `searchItems` | 改用 `HybridSearchService` 的 RRF 模式（不重排，最终选择交给助手的 LLM） |
| 修改 | `SearchController` | 默认模式改为 `RRF_RERANK` |
| 新增 | `eval/results/retrieval-<date>.md` | |
| 修改 | README | 检索架构、评测表 |

### P5b.3 关键实现

`VectorStoreConfig`：

```java
@Bean
RedisVectorStore catalogVectorStore(EmbeddingModel embeddingModel, RedisProperties redis) {
    JedisPooled jedis = new JedisPooled(redis.getHost(), redis.getPort());   // 有密码时带上密码
    return RedisVectorStore.builder(jedis, embeddingModel)
            .indexName("idx:catalog")
            .prefix("catalog:")
            .metadataFields(
                    RedisVectorStore.MetadataField.tag("type"),
                    RedisVectorStore.MetadataField.tag("category"),
                    RedisVectorStore.MetadataField.numeric("price"))
            .initializeSchema(true)
            .build();
}
```

API 名称以动手时的 Spring AI 版本为准。Spring Boot 的 Redis 自动配置用的是 Lettuce，这里单独建的 Jedis 连接与它并存。

`CatalogIndexer`：

- 文档 ID 为 `product:<id>` / `item:<id>`。
- 文本为 `title + " | " + category + " | " + description`。
- 元数据：`{type, refId, category, price}`。
- `@TransactionalEventListener(phase = AFTER_COMMIT, fallbackExecution = true)` 加 `@Async`：
  - 商品创建或更新 → upsert（先删除再新增）。
  - 商品删除，或物品状态变为非 ACTIVE → 删除。
- 必须设置 `fallbackExecution = true`：`ProductService` 和 `ItemService` 目前没有 `@Transactional`，没有这个参数事件会被丢掉。

`HybridSearchService` 的 RRF：

```java
Map<String, Double> score = new HashMap<>();
for (int i = 0; i < kw.size();  i++) score.merge(kw.get(i),  1.0 / (60 + i + 1), Double::sum);
for (int i = 0; i < vec.size(); i++) score.merge(vec.get(i), 1.0 / (60 + i + 1), Double::sum);
List<String> fused = score.entrySet().stream()
        .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
        .limit(10).map(Map.Entry::getKey).toList();
```

向量召回时，`maxPrice` / `category` 作为过滤表达式传给 `SearchRequest.filterExpression(...)`，`topK = 20`。

**没有 embedding 配置时应用必须仍能启动**（测试和未配置 key 的本地环境都依赖这一点）：`VectorStoreConfig` 加 `@ConditionalOnBean(EmbeddingModel.class)`；`HybridSearchService` 通过 `ObjectProvider<VectorStore>` 取向量库，取不到时 `VECTOR` / `RRF` / `RRF_RERANK` 模式抛 `BusinessException("vector search not configured")`，默认模式退回 `KEYWORD`；`CatalogIndexer` 在没有向量库时不做任何事。mcp-server 的 `application.yml` 同样要配置 embedding 和 Redis 连接，compose 里给 mcp-server 加上对应的环境变量。

`LlmReranker`：

- prompt 里放入查询，以及 10 个候选的 `ref | title | category | RM price | 描述前 120 个字符`。
- 要求模型只返回 JSON `{"ranking": ["product:1001", ...]}`，用 `.entity(RerankResult.class)` 解析。
- 解析失败、ref 不在候选里或数量不对时，退回 RRF 的顺序，并在 debug 里标记 `rerankFallback=true`。
- 通过 `ChatResponse` 的 metadata 记录 token 用量。
- 加上与助手相同的 `@CircuitBreaker(name = "llm")`，fallback 为 RRF 顺序。

### P5b.4 验收

- [ ] `app.catalog.reindex=true` 启动后，`FT.INFO idx:catalog` 的文档数 = 商品数 + ACTIVE 物品数。
- [ ] 在管理后台修改一个商品的名称，几秒内能用新名称的同义词搜到它。
- [ ] `run_retrieval_eval.py --modes keyword,vector,rrf,rrf_rerank` 的结果文件已提交，包含总表和分类型表；README 的评测表与结果文件一致。
- [ ] MCP 的 `search_products` 返回的是 RRF 结果。
- [ ] 结果不如预期时如实记录，**不要为了提高分数去修改评测集**。

---

## P6a · Testcontainers 集成测试 + GitHub Actions

### P6a.1 依赖（backend `pom.xml`，test scope）

`spring-boot-testcontainers`、`org.testcontainers:junit-jupiter`、`org.testcontainers:mysql`、`org.testcontainers:kafka`、`org.awaitility:awaitility`。另外在 `<build>` 里加 `maven-failsafe-plugin`，执行 `integration-test` 和 `verify` 两个 goal（版本由 Boot 管理），让 `*IT` 测试在 `verify` 阶段运行。

### P6a.2 测试基础设施

```java
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {
    @Bean @ServiceConnection
    MySQLContainer<?> mysql() { return new MySQLContainer<>("mysql:8.0"); }

    @Bean @ServiceConnection
    KafkaContainer kafka() { return new KafkaContainer("apache/kafka:3.9.1"); }   // org.testcontainers.kafka.KafkaContainer

    @Bean @ServiceConnection(name = "redis")
    GenericContainer<?> redis() { return new GenericContainer<>("redis:8").withExposedPorts(6379); }
}

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.ai.model.chat=none", "spring.ai.model.embedding=none", "spring.ai.mcp.client.enabled=false",
        "app.seed.enabled=false", "app.seckill.relay-interval-ms=50"})
@Import(TestcontainersConfiguration.class)
public abstract class AbstractIntegrationTest {
    // 工具方法：
    // createActiveEvent(stock)：直接写 products 和 seckill_events，startTime = now - 1s，然后调用 seckillService.reconcileEvents() 预热
    // tokenFor(userId)：用 JwtUtils 和一个只设置了 id/email/role/name 的 User 对象签发 JWT（不入库）
    // buy(token, eventId)：用 HttpClient 发请求
    // awaitOrders(eventId, n)：Awaitility，最多等 30 s
}
```

Flyway 会在 MySQL 容器上执行全部迁移，`ddl-auto: validate` 同时校验了实体与迁移是否一致。

### P6a.3 测试用例

| 类 | 场景 | 断言 |
| --- | --- | --- |
| `SeckillConcurrencyIT` | 用虚拟线程执行器发 2000 个不同用户的请求，库存 100 | 202 恰好 100 个，其余为 409；最终订单数 = 100，`COUNT(DISTINCT buyer_id)` = 100，`sold_count` = 100，Redis 库存为 0，outbox 待发送为 0 |
| `DuplicatePurchaseIT` | 同一用户并发发 20 次请求 | 恰好 1 个 202、1 张订单 |
| `ConsumerRestartIT` | 下单进行中用 `KafkaListenerEndpointRegistry` 停止所有容器，2 秒后再启动 | 订单数 = 已接受数 |
| `KafkaOutageIT` | 用 `DockerClientFactory.instance().client().pauseContainerCmd(id)` 暂停 Kafka 10 秒，期间下单 | 下单仍然返回 202；outbox 待发送数 > 0；恢复后订单全部落库 |
| `ReplayIT` | 落单完成后停止容器，用 `AdminClient.alterConsumerGroupOffsets` 把所有分区的 offset 重置为 0，再启动 | 订单数不变 |
| `OutboxRollbackIT` | 用 `@MockitoSpyBean` 让 `OutboxDao.insert` 抛异常 | 返回 503；Redis 库存和买家集合都恢复原状 |
| `ProductOrderRaceIT` | 库存为 1 的普通商品，50 个并发 `POST /api/orders` | 恰好 1 个成功；库存为 0 |
| `ItemOrderRaceIT` | 同一个 C2C 物品，20 个并发下单 | 恰好 1 个成功 |
| `HybridSearchServiceTest`（单元测试） | RRF 融合 | 两路名次已知时，融合后的顺序符合预期 |
| `OrderToolsTest`（单元测试） | P4b 已有的测试（P4b 未合并则跳过） | — |

- `KafkaOutageIT` 会改变共享容器的状态，加 `@DirtiesContext`，并保证结束时一定执行 unpause（放在 `finally` 里）。
- 原有的 5 个测试保留。

### P6a.4 `.github/workflows/ci.yml`

```yaml
name: ci
on:
  push:
    branches: [main]
  pull_request:
permissions:
  contents: read
  packages: write
jobs:
  backend:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '21', cache: maven }
      - run: ./mvnw -B verify
      - uses: actions/upload-artifact@v4
        if: always()
        with:
          name: test-reports
          path: '**/target/*-reports/'
  frontend:
    runs-on: ubuntu-latest
    defaults: { run: { working-directory: frontend } }
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with: { node-version: 20, cache: npm, cache-dependency-path: frontend/package-lock.json }
      - run: npm ci
      - run: npm run build
  images:
    needs: [backend, frontend]
    if: github.event_name == 'push' && github.ref == 'refs/heads/main'
    runs-on: ubuntu-latest
    strategy:
      matrix:
        include:
          - { name: backend,    context: .,        file: backend/Dockerfile }
          - { name: mcp-server, context: .,        file: mcp-server/Dockerfile }
          - { name: frontend,   context: frontend, file: frontend/Dockerfile }
    steps:
      - uses: actions/checkout@v4
      - uses: docker/setup-buildx-action@v3
      - uses: docker/login-action@v3
        with: { registry: ghcr.io, username: '${{ github.actor }}', password: '${{ secrets.GITHUB_TOKEN }}' }
      - uses: docker/build-push-action@v6
        with:
          context: ${{ matrix.context }}
          file: ${{ matrix.file }}
          push: true
          tags: ghcr.io/${{ github.repository_owner }}/ftsm-${{ matrix.name }}:${{ github.sha }}
          cache-from: type=gha
          cache-to: type=gha,mode=max
```

- 如果 P4b 尚未合并，先从 matrix 里删掉 mcp-server，并在 PR 里注明。
- GHCR 镜像名必须全小写；`repository_owner` 若含大写字母，用一个 step 先转成小写。
- README 顶部加 CI 徽章。

### P6a.5 验收

- [ ] 本地执行 `./mvnw -B verify`，全部 IT 通过，PR 里贴出 surefire 和 failsafe 的汇总（用例数和通过率）。
- [ ] PR 上的 CI 全绿；合并到 main 后，GHCR 里出现 3 个镜像。

---

## P6b · ShedLock、Kubernetes、OpenTelemetry、Grafana

### P6b.1 ShedLock（多副本前置）

- 依赖：`net.javacrumbs.shedlock:shedlock-spring`、`shedlock-provider-jdbc-template`。
- `V4__shedlock.sql`：

  ```sql
  CREATE TABLE shedlock (
    name VARCHAR(64) NOT NULL PRIMARY KEY,
    lock_until TIMESTAMP(3) NOT NULL,
    locked_at TIMESTAMP(3) NOT NULL,
    locked_by VARCHAR(255) NOT NULL
  );
  ```

- `config/SchedulingConfig`：`@EnableSchedulerLock(defaultLockAtMostFor = "PT1M")`，注册 `LockProvider` bean（`JdbcTemplateLockProvider`，`usingDbTime()`）。
- 加 `@SchedulerLock`：`reconcileEvents`（`lockAtMostFor = "PT30S"`）、`SeckillReconciler`、`OutboxJanitor`。
- **不要**给 `OutboxRelay` 加锁：`SKIP LOCKED` 本身就允许多副本并行发送。

### P6b.2 指标

- 依赖 `micrometer-registry-prometheus`；actuator 暴露 `health, info, prometheus, circuitbreakers`。
- `management.endpoint.health.probes.enabled: true`。
- 新增 `config/SeckillMetrics`：
  - Gauge `seckill.outbox.backlog`：每 5 秒调用一次 `countPending()` 并缓存结果，gauge 读取缓存值，不要每次 scrape 都查库。
  - Counter `seckill.buy{result=...}`：在 controller 里计数。
  - 对账的 mismatch counter：P2 已有。

### P6b.3 OpenTelemetry

`backend/Dockerfile` 和 `mcp-server/Dockerfile` 的 runtime 阶段：

```dockerfile
ARG OTEL_AGENT_VERSION=2.x.y        # 取最新稳定版
ADD https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OTEL_AGENT_VERSION}/opentelemetry-javaagent.jar /otel/opentelemetry-javaagent.jar
```

agent 只在设置了环境变量时启用：compose / K8s 中设置 `JAVA_TOOL_OPTIONS=-javaagent:/otel/opentelemetry-javaagent.jar`，并设置 `OTEL_SERVICE_NAME`、`OTEL_EXPORTER_OTLP_ENDPOINT=http://lgtm:4318`、`OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf`。agent 自带的 Micrometer 桥会把 Micrometer 指标一并通过 OTLP 发出，Grafana 直接查询即可。

**跨 outbox 的 trace 衔接**（否则 HTTP 请求和 Kafka 消费会是两条互不相连的 trace）：

- 依赖：`io.opentelemetry:opentelemetry-api`（agent 会桥接这个 API）。
- `V5__outbox_traceparent.sql`：`ALTER TABLE order_outbox ADD COLUMN traceparent VARCHAR(64) NULL;`
- `SeckillService.buy` 写 outbox 时，用 `W3CTraceContextPropagator.getInstance().inject(Context.current(), map, Map::put)` 取出 `traceparent`，一起写入该列。
- `OutboxPublisher` 发送每一行之前，先 `extract` 出 Context 并 `makeCurrent()`，然后在这个 scope 内执行 `kafka.send(...)`。这样 agent 生成的 producer span 会挂在原 HTTP trace 之下，并把上下文注入消息头，消费端自动延续。
- `OutboxDao.lockBatch` 的查询要带上 `traceparent` 列。

`docker-compose.yml` 新增：

```yaml
  lgtm:
    image: grafana/otel-lgtm:latest     # 固定到某个具体版本
    ports: ["3000:3000", "4317:4317", "4318:4318"]
```

`observability/grafana/dashboards/seckill.json` 包含 6 个面板：QPS、p99、outbox 积压、consumer lag（`kafka.consumer.fetch.manager.records.lag.max`，以 Grafana Explore 里实际显示的名称为准）、HikariCP 活跃连接数、熔断器状态。按 `grafana/otel-lgtm` 镜像文档里的 provisioning 方式挂载。**写 JSON 之前，先在 Explore 里确认每个指标的实际名称**（OTLP 转 Prometheus 时会把点换成下划线，并加上单位后缀）。

### P6b.4 Kubernetes `k8s/`

```text
k8s/
├── kustomization.yaml          # namespace: ftsm；images: 用 newTag 覆盖为 git sha
├── namespace.yaml
├── secrets.example.yaml        # JWT_SECRET, DB_PASSWORD, MCP_DB_PASSWORD, LLM_API_KEY, (OPENAI_API_KEY)
├── configmap.yaml              # 非敏感环境变量
├── mysql.yaml                  # StatefulSet(1) + headless Service + PVC；initdb ConfigMap 挂只读用户脚本
├── redis.yaml                  # StatefulSet(1)，redis:8 --appendonly yes
├── kafka.yaml                  # StatefulSet(1) KRaft；ADVERTISED_LISTENERS=PLAINTEXT://kafka-0.kafka.ftsm.svc.cluster.local:9092
├── backend.yaml                # Deployment(2) + Service backend:8080 + HPA + PVC uploads
├── mcp-server.yaml             # Deployment(1) + Service mcp-server:8081
├── frontend.yaml               # Deployment(1) + Service(NodePort 30080)，nginx 配置与 compose 相同（Service 名 backend 不变）
├── lgtm.yaml
└── README.md                   # kind 建集群、metrics-server、加载镜像、apply、压测、截图步骤
```

`backend.yaml` 要点：

```yaml
resources:
  requests: { cpu: 500m, memory: 768Mi }
  limits:   { memory: 1Gi }
livenessProbe:  { httpGet: { path: /actuator/health/liveness,  port: 8080 }, initialDelaySeconds: 40 }
readinessProbe: { httpGet: { path: /actuator/health/readiness, port: 8080 }, initialDelaySeconds: 20 }
```

- HPA：照搬 `UPGRADE_PLAN.md` 中的 YAML（min 2，max 6，CPU 70%）。
- 上传文件：kind 是单节点集群，用一个 `ReadWriteOnce` 的 local-path PVC，挂载到 `/app/uploads`；同一节点上的多个 Pod 可以共用同一个 RWO 卷。在 `k8s/README.md` 里写明：多节点集群需要换成 RWX 存储或对象存储。
- `k8s/README.md` 必须包含以下命令：
  - `kind create cluster`
  - metrics-server 的安装命令，以及给它加 `--kubelet-insecure-tls` 参数的 patch
  - `kind load docker-image ...`（或拉取 GHCR 镜像所需的 imagePullSecret）
  - `kubectl apply -k k8s/`
  - `kubectl get hpa -w`
  - 对 NodePort 跑 k6

### P6b.5 验收

- [ ] 用 `docker compose up` 下单一次，在 Grafana 的 Tempo 里能看到同一条 trace 依次包含：HTTP → Redis EVALSHA → MySQL INSERT order_outbox → Kafka publish → Kafka process → MySQL INSERT orders。
- [ ] 压测期间，dashboard 的 6 个面板都有数据。
- [ ] 在 kind 上执行 `kubectl apply -k k8s/` 后，所有 Pod 都变为 Ready，通过 NodePort 可以完成下单。
- [ ] 两个 backend 副本时，预热日志只出现在一个 Pod 上（ShedLock 生效）。
- [ ] 对集群跑 k6 时，`kubectl get hpa -w` 能看到副本数增加。**人**截图（HPA、Grafana、trace），放进 `docs/images/`，并在 README 中引用。

---

## P7 · GPT-4o Vision 上架助手（可选）

| 操作 | 路径 | 说明 |
| --- | --- | --- |
| 修改 | `backend/pom.xml` | `spring-ai-starter-model-openai`（如果 P5b 已选 OpenAI，则已存在） |
| 新增 | `backend/.../config/VisionConfig.java` | 手动构建 `OpenAiChatModel`（因为 `spring.ai.model.chat=deepseek`，自动配置不会创建 OpenAI 的 chat 模型），模型名取 `${OPENAI_VISION_MODEL:gpt-4o}` |
| 新增 | `backend/.../service/ListingDraftService.java` | 根据 `/uploads/<file>` 解析出本地文件 → `Media(MimeType, FileSystemResource)` → `.entity(ListingDraft.class)` |
| 修改 | `dto/ItemDtos.java` | `DraftFromImageRequest(@NotBlank String imageUrl)`，`ListingDraft(title, category, condition, suggestedPrice, description)` |
| 修改 | `controller/ItemController.java` | `POST /api/items/draft-from-image`（需登录） |
| 修改 | `application.yml` | `resilience4j.ratelimiter.instances.vision`（例如每分钟 10 次）；`OPENAI_API_KEY` |
| 修改 | `frontend/.../marketplace/SellItem.tsx`、`api.ts`、`types` | 图片上传成功后显示 “AI 自动填写” 按钮，返回结果填入表单，用户可以修改 |

约束：

- `imageUrl` 必须匹配 `^/uploads/[0-9a-f-]+\.(jpg|png|webp|gif)$`，并且解析后的路径必须位于 `UPLOAD_DIR` 之内，防止路径穿越。
- `category` 只能是 P5a 定义的类目之一（写在 prompt 里，返回后再校验）。
- 没有 `OPENAI_API_KEY` 时，该接口返回 400，提示功能未配置。

验收：上传一张实物照片，表单被合理填充（截图）；不合法的 `imageUrl` 返回 400；没有 key 时返回 400 且应用正常启动。

---

## 附：各阶段新增环境变量汇总

| 变量 | 引入阶段 | 默认值 | 用途 |
| --- | --- | --- | --- |
| `VIRTUAL_THREADS` | P1 | `true` | 压测矩阵 |
| `DB_POOL_SIZE` | P1 | `20` | Hikari 连接池 |
| `JAVA_OPTS` | P1 | `-XX:MaxRAMPercentage=75` | 容器 JVM 参数 |
| `KAFKA_BOOTSTRAP` | P2 | `localhost:9092` | |
| `KAFKA_REPLICAS` | P2 | `1` | |
| `SECKILL_MODE` | P3 | `async` | 仅压测时使用 `sync` |
| `LLM_PROVIDER` | P4b | `deepseek` | 设为 `none` 关闭助手 |
| `DEEPSEEK_BASE_URL` | P4b | `https://api.deepseek.com` | 取代 `LLM_BASE_URL` |
| `MCP_SERVER_URL` | P4b | `http://localhost:8081` | |
| `MCP_CLIENT_ENABLED` | P4b | `true` | |
| `MCP_DB_USERNAME` / `MCP_DB_PASSWORD` | P4b | `ftsm_ro` / — | 只读账号 |
| `SEED_DEMO_PASSWORD` | P5a | — | 演示卖家账号 |
| `OPENAI_API_KEY` | P5b / P7 | — | |
| `OLLAMA_BASE_URL` | P5b | `http://localhost:11434` | 选择 Ollama 方案时 |
| `OPENAI_VISION_MODEL` | P7 | `gpt-4o` | |
| `OTEL_*`、`JAVA_TOOL_OPTIONS` | P6b | 未设置（不启用 agent） | |
