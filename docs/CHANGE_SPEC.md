# FTSM 升级修改规格（逐文件）

设计理由见 `docs/UPGRADE_PLAN.md`，本文只写**怎么改**：每个阶段改哪些文件、改成什么样、怎么测、怎么验收。两份文档冲突时以本文为准。

---

## 0. 给执行 agent 的说明

### 0.1 本规格所依据的仓库事实（2026-10-03 核实）

| 事实 | 值 |
| --- | --- |
| `main` 分支 HEAD | `5f5fae4` — “perf(ai): default to deepseek-v4-flash”，对应 CHANGELOG **v0.4.2** |
| v0.4.0 / v0.4.1 对应提交 | `30ece89` / `e1321ff`（仅供参考，不作为基线） |
| v0.4.3、v0.4.4、v0.4.5 | 只存在于文档分支 `claude/inspiring-dijkstra-unfko2`，**尚未合并到 `main`**。v0.4.3 含 `docker-compose.yml` 的 `LLM_*` 环境变量修复和 `backend/pom.xml` 注释修改，**不是纯文档变更**；v0.4.4、v0.4.5 只改文档 |
| 仓库 tag | 无 |
| 后端构建 | `backend/pom.xml`：Spring Boot `3.3.5`，`java.version` 17，Lombok 覆盖为 1.18.36，springdoc 2.6.0；**没有 Maven Wrapper**；根目录没有 pom |
| 当前测试 | 5 个（3 个类），在 `5f5fae4` 上以 JDK 21.0.11 + Maven 3.9.11 执行 `mvn -B verify` 通过（2026-10-03 核实） |
| Schema 管理 | Hibernate `ddl-auto: update`，没有迁移工具；6 张表：`users`、`items`、`products`、`seckill_events`、`orders`、`reviews` |
| 配置 | `application.yml`：默认 profile 用 MySQL；`h2` profile 用内存库 + `create-drop`；Compose 设置的 `prod` profile 没有任何覆盖项 |
| Compose | `mysql:8.0`、`redis:7-alpine`（均为浮动 tag）、backend、frontend；`main` 上的 compose 仍在传 `GEMINI_*`（v0.4.3 已修，未合并） |
| 秒杀链路 | `SeckillService.buy`：`findById` 读活动 → `seckill_deduct.lua`（返回 1/0/-1/-2）→ `XADD seckill:orders` → 202；拒绝时返回 **200** + `result`；`SeckillStreamConsumer` 每 500 ms 用消费组 `seckill-order-consumers` 落单，并用 `FAKE_WALLET` 结算 |
| 助手 | `ChatService` 通过 WebClient 调 DeepSeek `/chat/completions`，按关键词把商品拼进 prompt；没有 tool calling；`ChatbotController` 阻塞等待结果 |

### 0.2 基线（所有文档统一使用）

| 项 | 值 |
| --- | --- |
| 基线代码提交 | `5f5fae4` |
| 基线版本 | v0.4.2 |
| 基线 tag | `v0.4.2-baseline`（由 P0 创建并推送） |
| 用途 | P0/P3 中的 “A-baseline” 压测配置；`V1__baseline.sql` 的 schema 来源 |

### 0.3 阶段、版本号与依赖

**P0 的唯一前置条件**：文档分支 `claude/inspiring-dijkstra-unfko2`（v0.4.3–v0.4.5）已经合并进 `main`。合并之后，`main` 上的代码仍与 `5f5fae4` 相同，只多了 compose 的 `LLM_*` 修复和文档。如果还没合并，P0 agent 必须停止并报告，不得自行合并。

| 阶段 | 版本 | 前置（必须已合并） | 内容 |
| --- | --- | --- | --- |
| P0 | v0.5.0 | 文档分支已合并 | 基线 tag、Maven Wrapper、Flyway V1、k6 脚本、冒烟基线 |
| P1 | v0.6.0 | P0 | Java 21、Boot 3.5.16、虚拟线程 |
| P2 | v0.7.0 | P1 | Outbox + Kafka；普通下单竞态修复 |
| P3 | v0.7.1 | P2 | sync/async 对照开关；正式压测（人工） |
| P4a | v0.8.0 | P1 | Maven 多模块，Wrapper 移到根目录（不改行为） |
| P4b | v0.9.0 | P4a | MCP server、Spring AI 助手、SSE、Resilience4j |
| P5a | v0.10.0 | P4b | 演示目录、FULLTEXT、`/api/search`、评测框架、评测查询草稿 |
| 人工 | — | P5a | 标注 `eval/queries.jsonl`；选定 embedding 提供方 |
| P5b | v0.11.0 | P5a + 人工两项 | 向量检索、RRF、LLM 重排、跑分 |
| P6a | v0.12.0 | P2、P4a | Testcontainers 集成测试 + GitHub Actions（使用根目录 Wrapper） |
| P6b | v0.13.0 | P6a、P4b | ShedLock、K8s、OpenTelemetry、Grafana |
| P7 | v0.14.0 | P4b | GPT-4o Vision 上架助手（可选，不阻塞其他阶段） |

规则：

- **推荐的串行顺序**：P0 → P1 → P2 → P3 → P4a → P4b → P5a →（人工）→ P5b → P6a → P6b →（P7）。
- **P2 与 P4a 不能同时进行**：P4a 会移动 P2 要修改的实体文件。两者必须先后合并。
- P3 的代码改动很小，正式压测由人执行，可以与 P4a 之后的阶段并行。

### 0.4 Maven 命令的工作目录

| 时期 | Wrapper 位置 | 命令 |
| --- | --- | --- |
| P4a 合并之前（P0–P3） | `backend/mvnw` | `cd backend && ./mvnw -B verify` |
| P4a 合并之后（P4a 及以后） | 仓库根 `./mvnw` | 在仓库根执行 `./mvnw -B verify`；只构建后端用 `./mvnw -B -pl backend -am package` |

如果实际合并顺序偏离推荐顺序（例如 P4a 先于 P2 合并），以 Wrapper 实际所在位置为准，并在 PR 里注明。

### 0.5 版本基线（固定版本或由 BOM 管理）

| 组件 | 版本 | 来源 / 说明 |
| --- | --- | --- |
| JDK | 21（P0 仍编译到 17 目标版本；P1 起编译到 21） | Temurin / OpenJDK |
| Maven（Wrapper） | 3.9.11，`distributionSha256Sum=0d7125e8c91097b36edb990ea5934e6c68b4440eef4ea96510a0f6815e7eeadb` | maven-wrapper-plugin 3.3.4，`only-script` |
| Spring Boot | P0：3.3.5（不变）；P1 起：**3.5.16** | 撰写时 3.5 线的最新版本 |
| Flyway | Boot 管理（3.3.5 下为 10.10.0，3.5.16 下为 11.7.2） | `flyway-core` + `flyway-mysql` |
| Lombok / Caffeine / spring-kafka / kafka-clients / Testcontainers / Awaitility | Boot 3.5.16 管理（分别为 1.18.46 / 3.2.4 / 3.3.16 / 3.9.2 / 1.21.4 / 4.2.2） | 不写版本号 |
| `com.redis:testcontainers-redis` | Boot 3.5.16 管理（2.2.4） | P6a |
| springdoc-openapi | 2.8.17 | P1 起；2.6.0 不兼容 Boot 3.5 |
| Spring AI BOM | 1.1.8 | P4b 起 |
| Resilience4j | 2.4.0（`resilience4j-spring-boot3`、`resilience4j-reactor`） | 不受 Boot 管理，在根 pom 用属性统一 |
| ShedLock | 6.10.0 | 7.x 面向 Spring 7，**不要用** |
| OpenTelemetry Java agent | 2.32.0，sha256 `f787eb6c7f3d18e69a431e108a15278d25ee37f83d68b678f621e063f3988f82` | P6b |
| Docker 镜像 | `mysql:8.0.46`、`redis:7.4.6-alpine`（P0–P1）、`redis:8.10.2`（P2 起）、`apache/kafka:3.9.2`、`grafana/otel-lgtm:0.35.0`、`grafana/k6:2.3.0`、`ollama/ollama:0.35.1`、`maven:3.9.11-eclipse-temurin-21`、`eclipse-temurin:21.0.8_9-jre` | 撰写时均已确认 tag 存在 |
| k6 | 2.3.0 | 本地二进制或 `grafana/k6:2.3.0` 镜像 |
| Python | ≥ 3.10，只用标准库 | 脚本 |
| Node | 20.20.2（与 `frontend/Dockerfile` 的 `node:20-alpine` 同一主版本） | CI |
| GitHub Actions | `actions/checkout@v4`、`actions/setup-java@v4`、`actions/setup-node@v4`、`actions/upload-artifact@v4`、`docker/setup-buildx-action@v3`、`docker/login-action@v3`、`docker/build-push-action@v6` | 主版本 tag |

任何阶段需要偏离上表时，必须在 PR 描述里写明原因和新版本。

### 0.6 使用方式

- 一个阶段 = 一个 agent 会话 = 一个 PR，从最新的 `main` 拉分支。
- 每个阶段的 agent 提示词模板（P0 的完整提示词见 §P0.10）：

  > 阅读 `docs/CHANGE_SPEC.md` 的 §0 和 §Pn，以及 `HANDOFF.md` §4 的代码约定。只实现 §Pn 列出的改动，不做“不在本阶段范围”里的事。逐条执行 §Pn 的验收，把命令和真实输出贴进 PR 描述；没有执行的验收项写明“未执行”和原因，不得写成通过。规格与代码对不上时，以代码现状为准做最小调整，并在 PR 里写明偏差。

- 规格里的代码是**目标形态的骨架**；import、getter、异常处理按现有风格补全。

### 0.7 全局约定（所有阶段适用）

| 项 | 约定 |
| --- | --- |
| 包结构 | 沿用 `controller / service / repository / model / dto / config / consumer / security / utils / exception`，根包 `my.edu.ukm.ftsm.ecommerce` |
| DTO | 按领域放进现有的 `XxxDtos` 记录类集合 |
| 错误 | 业务错误抛 `BusinessException`(400) / `ResourceNotFoundException`(404)，不在 controller 里手写错误 body |
| Redis key | 全部集中在 `utils/RedisKeys` |
| 配置 | 新配置放 `application.yml` 的 `app.*` 下，支持环境变量覆盖；新增的环境变量同步到 `.env.example`、`docker-compose.yml`、README 的 Configuration 一节 |
| 数据库 | P0 起，所有表结构变更都写成 Flyway 迁移 `backend/src/main/resources/db/migration/V{n}__{desc}.sql`；**已合并的迁移文件不得修改** |
| 文档 | 每个 PR：在 `CHANGELOG.md` 顶部加版本条目；更新 `HANDOFF.md` 的状态表和 API 列表；行为有变化时同步 README |
| 测试 | 每个 PR 结束时，按 §0.4 执行 `./mvnw -B verify` 和 `cd frontend && npm ci && npm run build`，两者都必须通过 |
| 密钥 | 只通过环境变量传入；不得提交 `.env` 或任何 key |
| 证据 | 验收输出贴进 PR 描述；需要长期保留的（压测 JSON、schema 对比结果）提交到规格指定的路径 |

### 0.8 所有阶段通用的停止规则

遇到下列情况，agent 必须停止相关步骤并在 PR 或会话里报告，不得绕过：

1. 开始时 `git status --porcelain` 不为空（工作区有用户未提交的改动）。**不得** stash、reset、checkout 或删除这些改动。
2. 依赖下载失败或 checksum 校验失败（Maven Wrapper、OTel agent、k6 二进制）。不得关闭校验，也不得换用未固定的版本。
3. 迁移与实体对不上（Flyway validate 失败、Hibernate `validate` 失败）。不得改回 `ddl-auto: update`，也不得修改已合并的迁移。
4. 正确性类验收失败（订单数不等于库存、出现重复订单）。不得调整断言来让它通过。
5. 规格要求的服务（Docker、MySQL、Kafka）不可用，并且 §P0.2 里的替代方式也不可用。
6. 需要人工决策的事项还没有决定（例如 P5b 的 embedding 提供方）。

---

## P0 · 基线、Maven Wrapper、Flyway、k6

### P0.1 目标与边界

**P0 只做这些：**

1. 创建基线 tag `v0.4.2-baseline` → `5f5fae4`。
2. 在 `backend/` 添加 Maven Wrapper（Maven 3.9.11，带 sha256 校验）。
3. 引入 Flyway：从真实 MySQL 导出当前 schema，作为 `V1__baseline.sql`；`ddl-auto` 改为 `validate`。
4. 把 `docker-compose.yml` 里的 `mysql`、`redis` 镜像改为固定 tag（`mysql:8.0.46`、`redis:7.4.6-alpine`）。
5. 添加 `loadtest/`（k6 脚本、准备/校验/汇总脚本）和 `scripts/db/`（schema 导出与核对）。
6. 在基线代码上跑小规模冒烟压测，结果存入 `loadtest/results/_smoke/`。

**P0 明确不做：** Java 21、Spring Boot 升级、Kafka、Outbox、Redis key 修改、Maven 多模块、Spring AI/MCP、任何业务逻辑修改、API 返回码修改、普通下单竞态修复、正式性能测量（由人做）。

### P0.2 前置条件与执行环境

| 项 | 要求 | 检查命令 |
| --- | --- | --- |
| 前置合并 | 文档分支已合并：`main` 上存在 `docs/CHANGE_SPEC.md`，且 `CHANGELOG.md` 含 v0.4.5 | `git log --oneline -1 origin/main && grep -c 'v0.4.5' CHANGELOG.md` |
| 工作目录 | 仓库根（下文称 `$REPO`）；命令里出现 `cd backend` 时，执行完需回到 `$REPO` | `git rev-parse --show-toplevel` |
| 工作区 | 干净 | `git status --porcelain` 输出为空 |
| JDK | 21.x（P0 仍编译到 17 目标版本，JDK 21 可以编译） | `java -version` |
| Maven | 3.9.x，只用于第一次生成 Wrapper；之后一律用 `./mvnw` | `mvn -v` |
| Docker | Engine ≥ 24，带 Compose v2（首选方式） | `docker info && docker compose version` |
| MySQL / Redis | 8.0.46 / 7.4.x；首选用 Docker 运行 | 见下 |
| Python | ≥ 3.10，只用标准库 | `python3 --version` |
| k6 | 2.3.0 | `k6 version`，或用下文的二进制 / Docker 方式 |
| Node / npm | 20+，用于 `npm ci && npm run build` | `node -v` |
| API key | **不需要任何 key**；LLM 相关环境变量留空 | — |
| `.env` | 不读取、不创建、不提交 | — |

**Docker 不可用时**（例如在没有 Docker 守护进程的云端容器里）：

- MySQL：`sudo apt-get install -y mysql-server-8.0 mysql-client-8.0`（Ubuntu 24.04 上为 8.0.46），然后 `sudo service mysql start`，并把 root 改为密码认证：`sudo mysql -e "ALTER USER 'root'@'localhost' IDENTIFIED WITH caching_sha2_password BY 'root'"`。端口用 3306，把下文所有命令里的 3307 改为 3306。
- Redis：`redis-server --port 6380 --daemonize yes`（7.0.x 也可以，P0 只用到 Lua 和 Stream）。
- k6：`curl -sL https://github.com/grafana/k6/releases/download/v2.3.0/k6-v2.3.0-linux-amd64.tar.gz | tar xz -C /tmp && export PATH=/tmp/k6-v2.3.0-linux-amd64:$PATH`。
- 在 PR 里注明用的是哪种方式。

**哪些情况下可以继续，哪些必须停止：**

| 情况 | 处理 |
| --- | --- |
| Docker 和 apt 都不可用 | 可以继续：Wrapper、编写脚本和配置、`cd backend && ./mvnw -B verify`（测试只用 H2）、前端构建。必须停止：P0.4 的 schema 导出（V1 不得手写）以及所有依赖 MySQL 的验收；PR 标为 draft，并写明“未执行”的项 |
| 只有 k6 不可用（二进制和镜像都下载失败） | 跳过冒烟压测，在 PR 里标记“未执行”；其余照常 |
| `git push` 或推送 tag 失败 | 本地提交照常；在 PR 或会话里报告；tag 由人补推（命令见 P0.3） |
| 前置合并未完成 | 停止，不开始任何改动 |

### P0.3 基线 tag

```bash
cd $REPO
git fetch origin main --tags
git cat-file -t 5f5fae4                      # 必须输出 commit
git tag -a v0.4.2-baseline 5f5fae4 -m "Pre-upgrade baseline (v0.4.2)"
git push origin v0.4.2-baseline
git ls-remote --tags origin v0.4.2-baseline  # 必须有一行输出
```

如果 tag 已经存在，确认它指向 `5f5fae4`（`git rev-parse v0.4.2-baseline^{commit}`）。指向别的提交时停止并报告，不得移动 tag。

### P0.4 Maven Wrapper

```bash
cd $REPO/backend
mvn -B -q -N org.apache.maven.plugins:maven-wrapper-plugin:3.3.4:wrapper \
  -Dmaven=3.9.11 \
  -DdistributionSha256Sum=0d7125e8c91097b36edb990ea5934e6c68b4440eef4ea96510a0f6815e7eeadb
./mvnw -v      # 必须输出 Apache Maven 3.9.11
```

生成的文件：`backend/mvnw`、`backend/mvnw.cmd`、`backend/.mvn/wrapper/maven-wrapper.properties`（`only-script` 模式，没有 jar）。确认 `mvnw` 带可执行位：`git ls-files -s backend/mvnw` 的第一列应为 `100755`。

从这一步起，P0–P3 的所有 Maven 命令都写成 `cd backend && ./mvnw ...`。

### P0.5 Flyway 与 `V1__baseline.sql`

#### P0.5.1 依赖与配置

`backend/pom.xml` 新增依赖（版本由 Boot 3.3.5 管理，即 Flyway 10.10.0）：

```xml
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-core</artifactId>
</dependency>
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-mysql</artifactId>
</dependency>
```

`backend/src/main/resources/application.yml`，默认 profile 部分：

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: validate            # 原为 update
  flyway:
    enabled: true
    locations: classpath:db/migration
    baseline-on-migrate: true       # 非空且没有 flyway_schema_history 的旧库：只记一条 BASELINE，不执行 V1
    baseline-version: 1
```

`h2` profile 部分追加：

```yaml
  flyway:
    enabled: false                  # H2 继续由 Hibernate create-drop 建表
```

`ReviewRepositoryTest` 的 `@DataJpaTest(properties = {...})` 里追加 `"spring.flyway.enabled=false"`。`@DataJpaTest` 会自动配置 Flyway，不关掉的话，会把 MySQL 方言的 V1 跑到 H2 上。

行为约定：

| 数据库状态 | Flyway 行为 | `flyway_schema_history` |
| --- | --- | --- |
| 空库 | 执行 V1 | `1 \| SQL \| V1__baseline.sql \| success=1` |
| 非空、由 Hibernate 建的旧库 | 只做基线化，**不执行** V1 | `1 \| BASELINE \| << Flyway Baseline >> \| success=1` |
| H2 profile / 测试 | 关闭 Flyway | 不存在 |

#### P0.5.2 导出脚本 `scripts/db/export_baseline_schema.sh`

这个脚本由 P0 新增并执行，并且必须可以重复执行。输入为环境变量：

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `BASELINE_JAR` | 必填 | 用 `v0.4.2-baseline` 构建出的 jar |
| `DB_HOST` / `DB_PORT` | `127.0.0.1` / `3307` | 临时 MySQL |
| `DB_USERNAME` / `DB_PASSWORD` | `root` / `root` | |
| `DB_NAME` | `ftsm_schema_export` | 必须是空库或不存在；脚本发现库里已有表时退出，退出码 2 |
| `MYSQL_DUMP` | `docker exec -i -e MYSQL_PWD=$DB_PASSWORD ftsm-p0-mysql mysqldump` | 不用 Docker 时改为 `env MYSQL_PWD=$DB_PASSWORD mysqldump -h$DB_HOST -P$DB_PORT` |
| `OUT` | `backend/src/main/resources/db/migration/V1__baseline.sql` | |

脚本步骤：

1. 用 `java -jar "$BASELINE_JAR"` 启动基线版本，传入以下环境变量：
   - `DB_URL="jdbc:mysql://$DB_HOST:$DB_PORT/$DB_NAME?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"`
   - `DB_USERNAME`、`DB_PASSWORD`
   - `SEED_ENABLED=false`（不写种子数据）、`SERVER_PORT=18080`、`REDIS_PORT=${REDIS_PORT:-6380}`
   - 基线版本的 `ddl-auto` 为 `update`，会让 Hibernate 建出真实 schema
2. 轮询日志，最多 120 秒，直到出现 `Started FtsmEcommerceApplication`；超时则打印日志末尾 50 行，退出码 1。
3. 停止进程（`kill`，然后 `wait`）。
4. 导出：`$MYSQL_DUMP -u$DB_USERNAME --no-data --compact --skip-add-drop-table --skip-comments --skip-set-charset $DB_NAME`。
5. 清洗：
   - 删除所有以 `/*!` 开头的行；
   - 删除所有以 `SET ` 开头的行；
   - 把表选项里的 ` AUTO_INCREMENT=<n>` 去掉；
   - 保留 `ENGINE`、`DEFAULT CHARSET`、`COLLATE`；
   - 在文件头加注释：`-- V1 baseline: exported from Hibernate-generated schema at 5f5fae4 (v0.4.2) on MySQL 8.0.46. Do not edit.`
6. 断言输出里恰好有 6 条 `CREATE TABLE`，分别是 `items`、`orders`、`products`、`reviews`、`seckill_events`、`users`；否则退出码 1。

准备工作：

```bash
cd $REPO
git worktree add ../fyp-baseline v0.4.2-baseline
(cd ../fyp-baseline/backend && $REPO/backend/mvnw -B -q -DskipTests package)
export BASELINE_JAR=$REPO/../fyp-baseline/backend/target/ecommerce-0.0.1-SNAPSHOT.jar
docker run -d --name ftsm-p0-mysql -e MYSQL_ROOT_PASSWORD=root -p 3307:3306 mysql:8.0.46
docker run -d --name ftsm-p0-redis -p 6380:6379 redis:7.4.6-alpine
until docker exec ftsm-p0-mysql mysqladmin -uroot -proot ping --silent; do sleep 2; done
bash scripts/db/export_baseline_schema.sh
```

#### P0.5.3 核对脚本 `scripts/db/verify_schema.sql`

```sql
-- 1. 表
SELECT table_name FROM information_schema.tables
WHERE table_schema = DATABASE() ORDER BY table_name;
-- 预期：items, orders, products, reviews, seckill_events, users（Flyway 管理的库还会多出 flyway_schema_history）

-- 2. 枚举列（Hibernate 6 在 MySQL 上把 @Enumerated(STRING) 建成 enum 列）
SELECT table_name, column_name, column_type FROM information_schema.columns
WHERE table_schema = DATABASE() AND data_type = 'enum' ORDER BY table_name, column_name;
-- 预期（以实际导出为准，但两边必须一致）：items.status, orders.source_type, orders.status,
--      reviews.target_type, seckill_events.status, users.role

-- 3. 索引
SELECT table_name, index_name, non_unique,
       GROUP_CONCAT(column_name ORDER BY seq_in_index) AS cols
FROM information_schema.statistics WHERE table_schema = DATABASE()
GROUP BY table_name, index_name, non_unique ORDER BY table_name, index_name;
-- 预期至少包含：每张表的 PRIMARY；users(email) 唯一；orders(tracking_token) 唯一

-- 4. 关键字段
SELECT table_name, column_name, column_type, is_nullable FROM information_schema.columns
WHERE table_schema = DATABASE() AND (table_name, column_name) IN (
  ('orders','buyer_id'), ('orders','ref_id'), ('orders','amount'), ('orders','tracking_token'),
  ('seckill_events','seckill_stock'), ('seckill_events','stock_warmed'), ('seckill_events','start_time'),
  ('products','total_stock'), ('items','item_condition'), ('users','email_verified'))
ORDER BY table_name, column_name;

-- 5. Flyway 历史（只在 Flyway 管理的库上执行）
SELECT installed_rank, version, type, script, success FROM flyway_schema_history ORDER BY installed_rank;
```

**结构等价检查**（P0 最关键的一项验收）：

1. 建两个库：
   - `ftsm_fresh`：空库，用 P0 代码启动，由 Flyway 执行 V1；
   - `ftsm_legacy`：先用基线 jar 启动（`SEED_ENABLED=true`），由 Hibernate 建表并写入种子数据；再用 P0 代码启动，由 Flyway 做基线化。
2. 对两个库分别执行（`$MYSQL_DUMP` 与 P0.5.2 中的相同）：

   ```bash
   $MYSQL_DUMP -uroot --no-data --compact --skip-comments <db> | sed -E 's/ AUTO_INCREMENT=[0-9]+//' | grep -v '^/\*!' > /tmp/<db>.sql
   ```

   去掉 `flyway_schema_history` 表的 CREATE 语句后再比较。
3. `diff /tmp/ftsm_fresh.sql /tmp/ftsm_legacy.sql` 必须为空。
4. 把 `verify_schema.sql` 在两个库上的输出和这次 diff 的结果一起保存到 `scripts/db/verify-output-p0.txt`。

### P0.6 k6 与压测脚本 `loadtest/`

```text
loadtest/
├── README.md          # 运行顺序、冒烟与正式测试的区别、环境记录模板
├── lib/jwt.js         # 在 k6 内签 HS256
├── throughput.js      # 吞吐场景
├── contention.js      # 争抢场景
├── setup_event.py     # 建商品 + 秒杀活动，等待 ACTIVE，打印 EVENT_ID
├── reset.sh           # 清理秒杀相关数据（破坏性操作，需确认）
├── verify.sql         # 订单核对
├── summarize.py       # 汇总结果 → Markdown 表
└── results/
    ├── _smoke/        # agent 冒烟结果，不得作为性能数据引用
    └── .gitkeep       # 正式结果目录（A-baseline/ 等）由人创建
```

所有脚本都从仓库根目录运行。

| 脚本 | 输入 | 依赖 | 预期 HTTP 状态 | 输出 | 失败行为 | 是否删除数据 |
| --- | --- | --- | --- | --- | --- | --- |
| `lib/jwt.js` | `userId`、`secret`（函数参数） | k6 内置 `k6/crypto`、`k6/encoding` | — | JWT 字符串 | — | 否 |
| `throughput.js` | `BASE_URL`、`EVENT_ID`、`JWT_SECRET`（≥ 32 字节，必须与后端一致）、`RATE`（默认 1000）、`RAMP_SECONDS`（默认 60）、`STEADY_SECONDS`（默认 120）、`REJECT_STATUS`（P0 默认 200；P2 起改为 409）、`SUMMARY_PATH`（必填） | k6 2.3.0 | 202；售罄或重复时为 `REJECT_STATUS` | `SUMMARY_PATH` 处的 JSON，见下 | 阈值不满足时 k6 退出码 99 | 否 |
| `contention.js` | `BASE_URL`、`EVENT_ID`、`JWT_SECRET`、`BUYERS`（默认 5000）、`STOCK`（默认 100，必须等于活动库存）、`VUS`（默认 `min(BUYERS, 1000)`）、`REJECT_STATUS`、`SUMMARY_PATH` | k6 2.3.0 | 恰好 `STOCK` 个 202，其余为 `REJECT_STATUS` | 同上 | `orders_accepted` 不等于 `STOCK`，或出现非预期状态码时，退出码 99 | 否 |
| `setup_event.py` | `--base`、`--stock`、`--admin-email`、`--admin-pass`（默认为种子管理员） | Python 标准库；后端已启动且种子数据开启 | 登录 200，建商品 200，建活动 200 | stdout 最后一行 `EVENT_ID=<id>` | 任一步失败或 20 秒内没有变为 ACTIVE：退出码 1 | 否（只新增数据） |
| `reset.sh` | `CONFIRM_RESET=yes`（必填）、`REDIS_CLI`（默认 `redis-cli -p 6380`）、`MYSQL_CLI`（默认 `docker exec -i -e MYSQL_PWD=root ftsm-p0-mysql mysql -uroot`）、`DB_NAME` | bash | — | 打印删除的数量 | 没有设置 `CONFIRM_RESET=yes` 时退出码 2 | **是**：删除 `seckill:stock:*`、`seckill:bought:*` 这些 key，对 `seckill:orders` 执行 `XTRIM MAXLEN 0`，删除 `orders` 里 `source_type='SECKILL'` 的行 |
| `verify.sql` | 会话变量 `@event_id` | mysql 客户端 | — | `orders`、`buyers` 两列 | — | 否 |
| `summarize.py` | `--dir <results 子目录>` | Python 标准库 | — | stdout 输出 Markdown 表 | 目录里没有 JSON，或 JSON 缺少 `meta`：退出码 1 | 否 |

`reset.sh` **不得**对 `seckill:orders` 执行 `DEL` 或 `FLUSHDB`：基线代码只在启动时创建一次消费组，删掉 stream 会连同消费组一起删除，之后的订单将不再落库，直到重启后端为止。

`lib/jwt.js`（已在 k6 2.3.0 上验证：`k6/crypto` 的 `hmac(..., 'base64rawurl')` 与 Python 的 HS256 结果一致）：

```javascript
import crypto from 'k6/crypto';
import encoding from 'k6/encoding';

const HEADER = encoding.b64encode(JSON.stringify({ alg: 'HS256', typ: 'JWT' }), 'rawurl');

// JwtAuthFilter only verifies the signature and reads sub/email/role; it never hits the DB.
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

`throughput.js` 的要点：

- 两个 scenario：`ramp`（`ramping-arrival-rate`，`RAMP_SECONDS` 秒内从 1 升到 `RATE`）和 `steady`（`constant-arrival-rate`，`startTime = RAMP_SECONDS`，持续 `STEADY_SECONDS`）。
- 用户 ID：`1_000_000 + exec.scenario.iterationInTest`，`steady` scenario 再加 `50_000_000`，保证两个 scenario 之间不重复。
- 阈值：
  - `'http_reqs{scenario:steady}': ['count>0']`：保证确实发出了请求；
  - `'http_req_failed{scenario:steady}': ['rate<0.01']`；
  - `'http_req_duration{scenario:steady}': ['p(99)<1000']`；
  - `summaryTrendStats: ['avg','p(50)','p(95)','p(99)','max']`。
- **必须用 `handleSummary` 计算稳定阶段的 QPS**。k6 导出的子指标 `rate` 是除以**整个测试时长**得到的，并不是稳定阶段的速率（已在 2.3.0 上验证：RATE=10 时，导出值为 5.16）。

  ```javascript
  export function handleSummary(data) {
    const steady = data.metrics['http_reqs{scenario:steady}'].values.count;
    const meta = { script: 'throughput', rate: RATE, rampSeconds: RAMP_SECONDS, steadySeconds: STEADY_SECONDS,
                   steadyRps: steady / STEADY_SECONDS, eventId: __ENV.EVENT_ID, rejectStatus: REJECT_STATUS,
                   baseUrl: __ENV.BASE_URL, finishedAt: new Date().toISOString() };
    return { [__ENV.SUMMARY_PATH]: JSON.stringify({ meta, metrics: data.metrics }, null, 2),
             stdout: `steadyRps=${meta.steadyRps.toFixed(1)} p99=${data.metrics['http_req_duration{scenario:steady}'].values['p(99)']}\n` };
  }
  ```

`contention.js` 的要点：

- executor 为 `shared-iterations`，`vus: VUS`，`iterations: BUYERS`。
- 用户 ID：`2_000_000 + exec.scenario.iterationInTest`。
- `http.setResponseCallback(http.expectedStatuses(202, REJECT_STATUS))`。
- 阈值：`orders_accepted: [\`count==${STOCK}\`]`、`http_req_failed: ['rate==0']`。阈值不满足时 k6 退出码为 99，已在 2.3.0 上验证。
- 同样用 `handleSummary` 写出 `meta`：`script`、`buyers`、`stock`、`accepted`、`eventId`。

`verify.sql`（基线代码没有 `seckill_event_id` 列，所以按 `ref_id` 查）：

```sql
SELECT COUNT(*) AS orders, COUNT(DISTINCT buyer_id) AS buyers
FROM orders WHERE source_type = 'SECKILL' AND ref_id = @event_id;
```

执行方式：

```bash
{ echo "SET @event_id=$EVENT_ID;"; cat loadtest/verify.sql; } | docker exec -i -e MYSQL_PWD=root ftsm-p0-mysql mysql -uroot <db>
```

`summarize.py`：读取 `--dir` 下全部 `*.json`，按 `meta.script` 分组：

- 吞吐场景：取 `steadyRps`，以及 `http_req_duration{scenario:steady}` 的 p50/p95/p99，三次运行取中位数；
- 争抢场景：列出 `accepted` 是否等于 `stock`。

输出 Markdown 表。

#### 冒烟测试与正式测试

| | agent 冒烟测试 | 人工正式测试 |
| --- | --- | --- |
| 谁执行 | P0 agent | 人，在配置固定的机器上 |
| 规模 | 吞吐：`RATE=10 RAMP_SECONDS=10 STEADY_SECONDS=20`；争抢：`BUYERS=20 STOCK=5 VUS=20` | 按 §P3.2 |
| 结果位置 | `loadtest/results/_smoke/` | `loadtest/results/A-baseline/` 等 |
| 能否引用为性能数据 | **不能**（云端容器资源不稳定） | 能，但必须附上环境记录 |
| 时机 | P0 内完成 | 可以在 P3 之前的任意时间做：tag 已经冻结了基线代码 |

没有本地 k6 时的 Docker 用法（Linux 用 `--network host`；macOS / Windows 改用 `-e BASE_URL=http://host.docker.internal:8080`）：

```bash
docker run --rm --network host -v "$PWD:/work" -w /work grafana/k6:2.3.0 run \
  -e BASE_URL=http://localhost:8080 -e EVENT_ID=$EVENT_ID -e JWT_SECRET=$JWT_SECRET \
  -e RATE=10 -e RAMP_SECONDS=10 -e STEADY_SECONDS=20 -e REJECT_STATUS=200 \
  -e SUMMARY_PATH=loadtest/results/_smoke/A-baseline-throughput.json \
  loadtest/throughput.js
```

### P0.7 文件清单

| 操作 | 路径 | 原因 | 验证命令 |
| --- | --- | --- | --- |
| 新增 | `backend/mvnw`、`backend/mvnw.cmd`、`backend/.mvn/wrapper/maven-wrapper.properties` | 固定 Maven 版本和 checksum | `cd backend && ./mvnw -v` |
| 修改 | `backend/pom.xml` | 只新增 `flyway-core`、`flyway-mysql` 两个依赖 | `cd backend && ./mvnw -B verify` |
| 新增 | `backend/src/main/resources/db/migration/V1__baseline.sql` | schema 基线（由脚本导出） | 结构等价检查 |
| 修改 | `backend/src/main/resources/application.yml` | `ddl-auto: validate`；Flyway 配置；h2 profile 关闭 Flyway | 空库 / 旧库 / H2 三种启动 |
| 修改 | `backend/src/test/java/my/edu/ukm/ftsm/ecommerce/repository/ReviewRepositoryTest.java` | 只在 properties 里加 `spring.flyway.enabled=false` | `cd backend && ./mvnw -B verify` |
| 修改 | `docker-compose.yml` | `mysql:8.0` → `mysql:8.0.46`；`redis:7-alpine` → `redis:7.4.6-alpine`；其余不变 | `docker compose config -q` |
| 新增 | `scripts/db/export_baseline_schema.sh`、`scripts/db/verify_schema.sql`、`scripts/db/verify-output-p0.txt` | 可复现的 schema 导出与核对 | P0.5 |
| 新增 | `loadtest/**`（见 P0.6） | 压测工具 | 冒烟测试 |
| 修改 | `README.md` | 构建命令改为 `cd backend && ./mvnw`；新增 “Database migrations” 和 “Load testing” 两节；H2 快速启动的说明保留 | — |
| 修改 | `HANDOFF.md` | 状态表加 Flyway / Wrapper / loadtest；§2 的命令改用 `cd backend && ./mvnw`；§8 删除 “No schema migrations” 这一条 | — |
| 修改 | `CHANGELOG.md` | v0.5.0 条目，列出所有执行过和未执行的验收项 | — |

**P0 不得修改**：

- `backend/src/main/java/**`
- `frontend/**`
- `scripts/e2e_test.py`
- `backend/Dockerfile`、`frontend/Dockerfile`、`frontend/nginx.conf`
- `.env.example`
- `backend/pom.xml` 中除新增两个 Flyway 依赖以外的任何内容（Boot 版本、Java 版本、Lombok、springdoc 均保持不变）

### P0.8 验收矩阵

下文的 P0 后端启动命令（记为 `RUN_P0`）：

```bash
cd $REPO/backend && ./mvnw -B -q -DskipTests package && cd $REPO
DB_URL="jdbc:mysql://127.0.0.1:3307/<db>?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC" \
DB_USERNAME=root DB_PASSWORD=root REDIS_PORT=6380 JWT_SECRET=p0-loadtest-secret-0123456789abcdef0123 \
SERVER_PORT=8080 java -jar backend/target/ecommerce-0.0.1-SNAPSHOT.jar > /tmp/ftsm-p0-<db>.log 2>&1 &
until curl -sf localhost:8080/actuator/health > /dev/null; do sleep 2; done   # 最多等 120 秒
```

基线 jar 的启动方式相同，只是把 jar 换成 `$BASELINE_JAR`。每次切换数据库之前，先停掉正在运行的后端进程。

| # | 验收项 | 命令 | 预期结果 | 必须保存的证据 |
| --- | --- | --- | --- | --- |
| 1 | Wrapper 可用 | `cd backend && ./mvnw -v` | Apache Maven 3.9.11；checksum 校验通过 | PR 描述 |
| 2 | 后端测试 | `cd backend && ./mvnw -B verify` | `Tests run: 5, Failures: 0, Errors: 0`，BUILD SUCCESS | PR 描述（结果汇总行） |
| 3 | 前端构建 | `cd frontend && npm ci && npm run build` | 退出码 0 | PR 描述 |
| 4 | 基线可启动 | 用 `$BASELINE_JAR` 在空库 `ftsm_legacy` 上启动（种子数据开启） | health 为 UP；日志里有 `Started FtsmEcommerceApplication` | PR 描述 |
| 5 | 空库 Flyway | `RUN_P0`，`<db>=ftsm_fresh` | 启动成功；`flyway_schema_history` 只有一行：`1 \| SQL \| V1__baseline.sql \| 1` | `verify-output-p0.txt` |
| 6 | 旧库基线化 | 停掉 #4 的进程，`RUN_P0`，`<db>=ftsm_legacy` | 启动成功；只有一行：`1 \| BASELINE`；种子管理员仍然能登录 | `verify-output-p0.txt` |
| 7 | 结构等价 | P0.5.3 的 diff | 输出为空 | `verify-output-p0.txt` |
| 8 | H2 profile | 停掉后端；`cd backend && SPRING_PROFILES_ACTIVE=h2 REDIS_PORT=6380 ./mvnw -B -q spring-boot:run`，看到 `Started` 后用 Ctrl-C 停止 | 启动成功；日志里没有 Flyway 迁移记录 | PR 描述 |
| 9 | e2e | `RUN_P0`，`<db>=ftsm_e2e`；`python3 scripts/e2e_test.py --base http://localhost:8080 --log /tmp/ftsm-p0-ftsm_e2e.log --users 60 --stock 20` | `8/8 checks passed` | PR 描述 |
| 10 | 吞吐冒烟（基线） | 启动基线 jar（`<db>=ftsm_smoke`，`JWT_SECRET` 同上）；`python3 loadtest/setup_event.py --base http://localhost:8080 --stock 100000`；按 P0.6 的冒烟参数运行 `throughput.js` | k6 退出码 0；`meta.steadyRps > 0`；`http_reqs{scenario:steady}.count > 0` | `loadtest/results/_smoke/A-baseline-throughput.json` |
| 11 | 争抢冒烟（基线） | 同一个后端；`setup_event.py --stock 5`；`contention.js`，参数 `BUYERS=20 STOCK=5 VUS=20 REJECT_STATUS=200` | k6 退出码 0；`orders_accepted == 5` | `loadtest/results/_smoke/A-baseline-contention.json` |
| 12 | 接受数 = 库存 = 落库数 | #11 结束后等 5 秒，执行 `verify.sql`；再执行 `redis-cli -p 6380 GET seckill:stock:$EVENT_ID` | `orders=5, buyers=5`；Redis 返回 `0` | `loadtest/results/_smoke/A-baseline-verify.txt` |
| 13 | 争抢冒烟（P0 代码） | 停掉基线，`RUN_P0`（`<db>=ftsm_smoke`，此时会触发基线化），重复 #11 和 #12 | 与 #11、#12 相同 | `loadtest/results/_smoke/P0-contention.json`、`P0-verify.txt` |
| 14 | 汇总脚本 | `python3 loadtest/summarize.py --dir loadtest/results/_smoke` | 输出表格，退出码 0 | PR 描述 |
| 15 | 改动范围 | `git diff --name-only origin/main...HEAD` | 只出现 P0.7 列出的路径；`git diff --name-only origin/main...HEAD -- backend/src/main/java frontend scripts/e2e_test.py` 输出为空 | PR 描述 |
| 16 | 没有提交密钥 | `git diff origin/main...HEAD \| grep -iE 'api[_-]?key\|secret' \| grep -v 'p0-loadtest-secret\|JWT_SECRET'` | 输出为空 | PR 描述 |
| 17 | tag | `git ls-remote --tags origin v0.4.2-baseline` | 输出一行，指向 `5f5fae4` | PR 描述 |

收尾：删除临时容器（`docker rm -f ftsm-p0-mysql ftsm-p0-redis`）和 worktree（`git worktree remove ../fyp-baseline`）。

### P0.9 P0 专属的失败规则

除了 §0.8 的通用规则，下列情况也必须停止并报告：

| 情况 | 处理 |
| --- | --- |
| 基线 jar 起不来，或 schema 导出脚本失败 | 停止。不得手写 V1；不得用 H2 生成 V1 |
| 结构等价 diff 不为空 | 停止；把 diff 贴进 PR。不得手工修改 V1 去凑结果 |
| Flyway 或 Hibernate validate 失败 | 停止。不得改回 `update`，也不得关闭 validate |
| k6 的 `http_reqs` 为 0，或 k6 退出码不为 0 | 先排查脚本；属于脚本问题就修，属于后端问题就停止。不得删除阈值 |
| 争抢冒烟的接受数不等于 5，或落库数不等于接受数 | 停止。这是基线代码的正确性问题，必须由人确认 |
| Wrapper、k6 二进制、镜像下载失败，或 checksum 不匹配 | 停止相关步骤，报告完整的 URL 和错误。不得换用别的版本 |
| tag 已存在，但指向的不是 `5f5fae4` | 停止，不得移动 tag |

**PR 描述里所有没有执行的验收项，必须标明“未执行”及原因。**

### P0.10 P0 执行 prompt（直接交给 agent）

见本文末尾的 “附 B · P0 执行 prompt”。

---

## P1 · Java 21 + Spring Boot 3.5 + 虚拟线程

### P1.1 文件清单

| 操作 | 路径 | 改动 |
| --- | --- | --- |
| 修改 | `backend/pom.xml` | parent `3.3.5` → `3.5.16`；`java.version` 17 → 21；删除 `<lombok.version>` 覆盖（改用 Boot 管理的 1.18.46）；`springdoc-openapi-starter-webmvc-ui` 2.6.0 → `2.8.17`（2.6 不兼容 Boot 3.5）；Flyway 版本随 Boot 变为 11.7.2 |
| 修改 | `backend/Dockerfile` | 见下 |
| 修改 | `application.yml` | 虚拟线程、Hikari 连接池大小 |
| 修改 | README / HANDOFF | JDK 要求改为 21；删除“构建目标 Java 17”的说法；JDK 25 的警告改为“需 Lombok ≥ 1.18.40” |

3.5.16 是撰写时 3.5 线的最新版本。如果动手时 spring.io 的支持表显示 3.5 已经停止开源支持，暂停本阶段并在 PR 里说明，由人决定是否改走 Boot 4（Boot 4 需要 Spring AI 2.x、ShedLock 7.x，后续阶段的版本都要随之调整）。

Boot 升级后 Hibernate 也会升级（3.5.16 下为 6.6.x）。`ddl-auto: validate` 必须仍然通过，否则按 §0.8 第 3 条停止：**不得**通过修改 V1 来迁就新版本，只能新增一个迁移。

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
FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -e dependency:go-offline
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21.0.8_9-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
```

### P1.4 验收

后端的启动方式沿用 §P0.8 的 `RUN_P0`。

- [ ] `cd backend && ./mvnw -B verify` 通过；`docker compose build backend` 成功。
- [ ] 在 P0 建好的旧库（已基线化）和一个空库上分别启动：Flyway 与 `validate` 都通过。
- [ ] `python3 scripts/e2e_test.py --base http://localhost:8080 --log <日志> --users 60 --stock 20` 8/8。
- [ ] 在 `java` 命令后加 `-Djdk.tracePinnedThreads=short`（JDK 21 支持这个参数）启动，跑 `scripts/e2e_test.py --users 200 --stock 50`（需要换一个空库，或者先清掉 e2e 用户），把日志中 pinning 堆栈的数量和来源贴进 PR。
- [ ] `loadtest/contention.js` 冒烟（`BUYERS=20 STOCK=5`，`REJECT_STATUS=200`）通过，结果存为 `loadtest/results/_smoke/P1-contention.json`。
- [ ] Swagger UI（`/swagger-ui.html`）可以打开。

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
| 新增 | `service/OutboxRelay.java`、`service/OutboxPublisher.java` | outbox → Kafka（调度与事务分成两个 bean） |
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
| 修改 | `docker-compose.yml` | 加 `apache/kafka:3.9.2`；redis 从 `redis:7.4.6-alpine` 换成 `redis:8.10.2` 并开启 AOF |
| 修改 | `frontend/src/types/index.ts` | `SeckillBuyResponse.result` 加 `'UNAVAILABLE'` |
| 修改 | `scripts/e2e_test.py` | 第 7 步改为轮询等待 |
| 修改 | `loadtest/throughput.js`、`loadtest/contention.js`、`loadtest/README.md` | `REJECT_STATUS` 默认值从 200 改为 409；README 写明基线（A-baseline）测试要显式传 `REJECT_STATUS=200` |
| 修改 | `loadtest/reset.sh`、`loadtest/verify.sql` | reset 去掉 `XTRIM`（已经没有 stream），增加 `TRUNCATE order_outbox`；verify 改用 `seckill_event_id`，并多输出 `sold_count`。基线测试用的旧版查询另存为 `verify_legacy.sql` |
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

    /** Same as the removed SeckillStreamConsumer: FakeWalletStrategy, always succeeds -> order becomes PAID. */
    private static final String SECKILL_PAYMENT = "FAKE_WALLET";

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

支付说明：秒杀订单沿用现有行为，用 `FAKE_WALLET`（`FakeWalletStrategy.pay` 恒返回 true）在同一个事务里结算，订单最终状态为 `PAID`。不改用 `MOCK_FPX`：它有 10% 的失败率，会让“订单数 = 库存”的验收变得不确定。

### P2.12 对账 `SeckillReconciler`

`@Scheduled(fixedDelay = 60_000)`。处理对象：`status=ENDED` 且 `reconciled=false` 的活动。设 `grace = app.seckill.reconcile-grace`（默认 30 分钟）。

对每个这样的活动，按顺序执行：

1. `endTime` 距今不足 2 分钟：跳过，本轮不处理。
2. `outboxDao.countPendingForEvent(id) > 0`：跳过（还有消息没有发出去）。
3. 读取下面四个数：

| 数 | 来源 |
| --- | --- |
| `redisSold` | `seckillStock - GET seckill:stock:{id}` |
| `soldCount` | `seckill_events.sold_count` |
| `orderCount` | `orderRepository.countBySeckillEventId(id)` |
| `pending` | 已确认为 0 |

4. 根据结果处理：

| 情况 | 是否设置 `reconciled=true` | 日志 / 指标 |
| --- | --- | --- |
| Redis 不可用（读 key 时抛异常） | **否**，下一轮重试 | WARN，内容为 “redis unavailable, will retry” |
| key 不存在（例如被手工删除） | 在 `endTime + grace` 之前：否；之后：**是** | 设置时打 WARN “redis key missing, compared MySQL only”，计数器 `seckill.reconcile.incomplete` 加 1；此时只比较 `soldCount` 和 `orderCount` |
| 三个数一致 | **是** | INFO |
| 三个数不一致 | 在 `endTime + grace` 之前：**否**（消费者可能还有积压，下一轮重试，只打 DEBUG）；之后：**是** | 设置时打 WARN，列出全部数字，计数器 `seckill.reconcile.mismatch` 加 1。`redisSold > orderCount` 表示名额丢失（少卖），日志里要写明 |

不一致不会自动修复，只报告。设置 `reconciled=true` 的意思是“已给出最终结论”，不代表“结果一致”。

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
    reconcile-grace: 30m
```

同时删除 `on-profile: h2` 整段。

`docker-compose.yml`：

```yaml
  redis:
    image: redis:8.10.2
    command: ["redis-server", "--appendonly", "yes"]
    # 其余不变

  kafka:
    image: apache/kafka:3.9.2     # 与 Boot 3.5.16 管理的 kafka-clients 3.9.2 一致
    restart: unless-stopped
    environment:
      KAFKA_NODE_ID: 1
      KAFKA_PROCESS_ROLES: broker,controller
      KAFKA_LISTENERS: PLAINTEXT://:9092,CONTROLLER://:9093,EXTERNAL://:29092
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:9092,EXTERNAL://localhost:29092
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: PLAINTEXT:PLAINTEXT,CONTROLLER:PLAINTEXT,EXTERNAL:PLAINTEXT
      KAFKA_CONTROLLER_QUORUM_VOTERS: 1@kafka:9093
      KAFKA_CONTROLLER_LISTENER_NAMES: CONTROLLER
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_MIN_ISR: 1
      KAFKA_LOG_DIRS: /var/lib/kafka/data
    ports:
      - "29092:29092"             # 宿主机上的后端 / 工具通过 localhost:29092 连接
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

在顶层 `volumes:` 里加上 `kafka_data:`。容器内的服务使用 `kafka:9092`，宿主机上的进程使用 `localhost:29092`，在 README 里写明这一点。

`scripts/e2e_test.py` 第 7 步：把固定的 `time.sleep(3)` 改成最多 20 秒的轮询，直到赢家能查到 SECKILL 订单为止。

### P2.15 验收（命令和输出都要贴进 PR）

环境：`docker compose up -d mysql redis kafka`。后端在宿主机上用 `RUN_P0` 的方式启动，并加上环境变量 `KAFKA_BOOTSTRAP=localhost:29092`（compose 里的 kafka 需要配置 EXTERNAL listener，见 P2.14）；也可以整套用 compose 启动。

- [ ] `cd backend && ./mvnw -B verify`；`cd frontend && npm ci && npm run build`。
- [ ] 在 P1 的旧库上启动：Flyway 执行 V2，`validate` 通过。
- [ ] `python3 scripts/e2e_test.py --users 200 --stock 50 --log <日志>` 8/8，之后：`SELECT COUNT(*), COUNT(DISTINCT buyer_id), (SELECT sold_count FROM seckill_events WHERE id=?) FROM orders WHERE seckill_event_id=?` 三个数都是 50；`redis-cli GET 'seckill:stock:{<id>}'` 是 0。
- [ ] 抢购进行中执行 `docker compose kill backend` 再 `up -d backend`：订单数 = 已接受数。
- [ ] 执行 `docker compose pause kafka` 30 秒，期间下单仍然返回 202，`order_outbox` 里 `status=0` 的行数在增长；`unpause` 之后积压被清零，订单全部落库。
- [ ] 停掉 backend，执行 `docker compose exec kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --group seckill-order-writer --reset-offsets --to-earliest --all-topics --execute`，再启动 backend：订单数不变，日志中出现 duplicate skipped。
- [ ] 两个用户并发购买同一个库存为 1 的普通商品：只有一个成功。
- [ ] 活动结束 2 分钟后，对账日志显示三个数一致，且 `reconciled=1`。
- [ ] Redis 停掉的情况下，活动结束后 `reconciled` 仍然为 0，并且日志里有 “will retry”。
- [ ] `loadtest/contention.js` 冒烟（`BUYERS=20 STOCK=5`，使用默认的 `REJECT_STATUS=409`）通过，结果存为 `loadtest/results/_smoke/P2-contention.json`。

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

本阶段分成两部分：**agent 的 PR** 和**人的正式测试**。

**agent 的 PR（v0.7.1）**

- 实现 sync 开关。
- 在 `summarize.py` 里增加 “按配置分列” 的输出。
- 在 README 里放一个 Performance 表格模板，数字写 `TBD`。
- 用 sync 和 async 两种模式各跑一次争抢冒烟（`BUYERS=20 STOCK=5`），结果存为 `_smoke/P3-sync-contention.json`、`_smoke/P3-async-contention.json`。

**人的正式测试**（可以晚于 PR 合并，单独提交）：

- 在配置固定的机器上执行：
  - A 配置：`git worktree add ../fyp-baseline v0.4.2-baseline`，`REJECT_STATUS=200`，旧版查询用 `verify_legacy.sql`；
  - B 配置：`SECKILL_MODE=sync`；
  - C 配置：默认（async）。
- 每次运行之前先执行 `CONFIRM_RESET=yes loadtest/reset.sh` 并新建活动。吞吐场景的活动库存设为 1,000,000，对应商品的 `totalStock` 也要设为 1,000,000。
- `RATE` 从 1000 开始，按 500 一档往上加，直到稳定阶段 p99 超过 1 s 或错误率超过 1%。报告其中最高的、仍然达标的那一档；QPS 取 `meta.steadyRps`。
- 每档跑完后，等 outbox 积压和 consumer lag 都归零，再执行 verify，结果写进同目录的 `verify-<n>.txt`。
- 用 `summarize.py` 生成 README 里的表格，并填写环境记录。

### P3.3 验收

agent 的 PR：

- [ ] `cd backend && ./mvnw -B verify`。
- [ ] 两份冒烟 JSON 都存在，`accepted == stock`，verify 中 `orders == buyers == sold_count == 5`。
- [ ] 默认模式（`SECKILL_MODE` 不设置）仍然是 async：outbox 里有记录。

人的正式测试：

- [ ] 三个配置目录下，各有吞吐 3 次、争抢 3 次的 JSON 和 verify 文件。
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
    <version>3.5.16</version>
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
| Wrapper | `git mv backend/mvnw mvnw && git mv backend/mvnw.cmd mvnw.cmd && git mv backend/.mvn .mvn`；从此在仓库根执行 `./mvnw`（§0.4） |
| `backend/Dockerfile` | 构建上下文改为仓库根目录；构建阶段仍用 `maven:3.9.11-eclipse-temurin-21`：先 `COPY pom.xml`、`catalog-core/`、`backend/`，再 `mvn -B -q -pl backend -am -DskipTests package`；用 `RUN --mount=type=cache,target=/root/.m2` 缓存依赖（文件第一行加 `# syntax=docker/dockerfile:1.7`） |
| `docker-compose.yml` | `backend.build` 改为 `{ context: ., dockerfile: backend/Dockerfile }` |
| 新增 `.dockerignore`（根目录） | 排除 `**/target`、`frontend/node_modules`、`.git`、`loadtest/results` |
| README / HANDOFF | 本地运行改为在仓库根执行 `./mvnw -B -pl backend -am -DskipTests package && java -jar backend/target/ecommerce-0.0.1-SNAPSHOT.jar`（带 `-am` 时，`spring-boot:run` 也会在 catalog-core 上执行并失败，所以不用它）；更新项目结构；删除所有 `cd backend && ./mvnw` 的写法 |
| `loadtest/README.md`、`scripts/db/*` | 其中的 Maven 命令改为根目录写法 |

测试仍然放在 `backend/src/test`（`ReviewRepositoryTest` 测的是 catalog-core 里的仓库，但需要 backend 的启动类）。

### P4a.4 验收

- [ ] 在仓库根执行 `./mvnw -B verify` 通过，测试数量与拆分前一致；`ls backend/mvnw` 应不存在。
- [ ] `grep -rn 'cd backend && ./mvnw' README.md HANDOFF.md loadtest scripts` 输出为空。
- [ ] `docker compose build backend && docker compose up -d`，`python3 scripts/e2e_test.py --log <backend 日志>` 8/8（日志用 `docker compose logs backend > /tmp/b.log` 获取）。
- [ ] `git diff --stat -M` 中，移动的文件显示为 rename（内容未改）。

---

## P4b · MCP server + Spring AI 助手 + SSE + Resilience4j

版本：Spring AI BOM `1.1.8`（面向 Boot 3.5），Resilience4j `2.4.0`。动手前用一个最小请求确认 `LLM_MODEL`（默认 `deepseek-v4-flash`）支持 tool calling；不支持就暂停，并在 PR 里说明。

本阶段的三条硬性要求，各自都有自动化测试（见 P4b.6）：

1. 没有 `LLM_API_KEY` 时，应用照常启动，助手返回 “not configured”。
2. mcp-server 不可用时，应用照常启动，助手降级为不调用工具的回答。
3. 流式接口在异步分派阶段依然受鉴权保护；未认证的请求被拒绝。

### P4b.1 文件清单

| 操作 | 路径 | 说明 |
| --- | --- | --- |
| 修改 | 根 `pom.xml` | `<module>mcp-server</module>`；dependencyManagement 导入 `org.springframework.ai:spring-ai-bom:1.1.8`；属性 `<resilience4j.version>2.4.0</resilience4j.version>` |
| 新增 | `catalog-core/.../search/CatalogSearchService.java` | 本阶段只做关键词检索（现有 LIKE 逻辑），P5 再替换 |
| 新增 | `catalog-core/.../search/SearchDtos.java` | `ProductView`、`ItemView`、`StockView`、`FlashSaleView` |
| 修改 | `catalog-core/.../repository/SeckillEventRepository.java` | `findByProductIdAndStatus` |
| 新增 | `mcp-server/pom.xml`、`Dockerfile`、`src/main/resources/application.yml` | |
| 新增 | `mcp-server/.../mcp/McpServerApplication.java`、`ShopTools.java`、`ToolsConfig.java` | |
| 新增 | `mysql/init/01-readonly-user.sh` | 只读账号 |
| 修改 | `backend/pom.xml` | Spring AI、MCP client、Resilience4j 依赖 |
| 新增 | `backend/.../service/AssistantService.java` | 取代 `ChatService` |
| 新增 | `backend/.../service/OrderTools.java` | 本地 `@Tool`，用户 ID 来自 ToolContext |
| 新增 | `backend/.../service/ToolCallRecorder.java` | 记录**全部**工具调用（MCP 工具和本地 `my_orders`），供评测使用 |
| 新增 | `backend/.../service/McpToolsProvider.java` | 延迟连接 MCP server，失败时返回空工具集，并定时重试 |
| 新增 | `backend/.../controller/AssistantController.java` | `POST /api/assistant/stream` |
| 修改 | `backend/.../controller/ChatbotController.java` | 内部改为调用 `AssistantService` 并聚合结果 |
| 删除 | `backend/.../service/ChatService.java`、`config/WebClientConfig.java` | |
| 修改 | `backend/.../security/JwtAuthFilter.java`、`config/SecurityConfig.java` | 把 SecurityContext 存入 `RequestAttributeSecurityContextRepository`，使异步分派阶段能恢复认证信息 |
| 新增 | `backend/src/test/.../controller/AssistantSecurityTest.java` | 未认证被拒；认证后异步分派返回 200 |
| 新增 | `backend/src/test/.../service/AssistantServiceTest.java` | 无 ChatClient、无 MCP 时的降级行为 |
| 新增 | `backend/src/test/.../service/OrderToolsTest.java` | 只使用 ToolContext 中的 userId |
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

`mysql/init/01-readonly-user.sh`（compose 中挂载到 mysql 的 `/docker-entrypoint-initdb.d/`，只在数据卷为空时执行；已有数据卷时，用 `docker compose exec mysql bash /docker-entrypoint-initdb.d/01-readonly-user.sh` 手动执行一次，写进 README）。

密码**不得**直接拼进 SQL 文本。做法是先校验字符集，校验失败就拒绝执行。这样能保证密码里不可能出现引号、反引号或反斜杠，SQL 注入也就无从谈起：

```bash
#!/bin/bash
set -euo pipefail
: "${MCP_DB_PASSWORD:?MCP_DB_PASSWORD is required}"
# Only [A-Za-z0-9._~-], 24-128 chars (generate with: openssl rand -hex 24). Anything else is
# rejected, so the value can never break out of the SQL string literal below.
if [[ ! "$MCP_DB_PASSWORD" =~ ^[A-Za-z0-9._~-]{24,128}$ ]]; then
  echo "MCP_DB_PASSWORD must match ^[A-Za-z0-9._~-]{24,128}\$" >&2; exit 1
fi
if [[ ! "$MYSQL_DATABASE" =~ ^[A-Za-z0-9_]{1,64}$ ]]; then
  echo "MYSQL_DATABASE has unexpected characters" >&2; exit 1
fi
MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot <<SQL
CREATE USER IF NOT EXISTS 'ftsm_ro'@'%' IDENTIFIED BY '${MCP_DB_PASSWORD}';
ALTER USER 'ftsm_ro'@'%' IDENTIFIED BY '${MCP_DB_PASSWORD}';
GRANT SELECT ON \`${MYSQL_DATABASE}\`.* TO 'ftsm_ro'@'%';
SQL
```

root 密码通过 `MYSQL_PWD` 环境变量传入，不出现在命令行参数里。`.env.example` 中写明生成方式：`MCP_DB_PASSWORD=$(openssl rand -hex 24)`。验收时用一个含单引号的密码测试，脚本必须以退出码 1 拒绝。

compose 中给 mysql 服务加环境变量 `MCP_DB_PASSWORD`；新增 `mcp-server` 服务（构建上下文为根目录，`dockerfile: mcp-server/Dockerfile`，端口 8081，`depends_on` mysql 和 redis 健康，并配置基于 actuator health 的 healthcheck；先确认运行镜像里有 `curl` 或 `wget`，没有就在 Dockerfile 里安装，或者改用 `bash -c '</dev/tcp/localhost/8081'` 做 TCP 检查）。

### P4b.3 backend：助手

依赖：

- `spring-ai-starter-model-deepseek`、`spring-ai-starter-mcp-client`（Spring AI BOM 1.1.8 管理）；
- `io.github.resilience4j:resilience4j-spring-boot3`、`resilience4j-reactor`（`${resilience4j.version}`）；
- `spring-boot-starter-aop`（Boot 管理）；
- 保留 `spring-boot-starter-webflux`，Spring AI 的流式调用需要它。

`application.yml`（删除 `app.llm.*`）：

```yaml
spring:
  ai:
    model:
      chat: ${LLM_PROVIDER:none}             # 默认关闭；只有设为 deepseek 才会创建 ChatModel
    deepseek:
      api-key: ${LLM_API_KEY:}
      base-url: ${DEEPSEEK_BASE_URL:https://api.deepseek.com}   # 注意：不带 /v1
      chat:
        options:
          model: ${LLM_MODEL:deepseek-v4-flash}
          temperature: 0.3
    mcp:
      client:
        enabled: false                       # 不用自动配置（它在启动时连接 MCP server，连不上会导致启动失败），改用 McpToolsProvider
  mvc:
    async:
      request-timeout: 60s

app:
  assistant:
    mcp-url: ${MCP_SERVER_URL:http://localhost:8081}
    mcp-enabled: ${MCP_CLIENT_ENABLED:true}
    mcp-retry-interval: 30s

resilience4j:
  ratelimiter:
    instances:
      llm: { limit-for-period: 20, limit-refresh-period: 1s, timeout-duration: 0 }
  circuitbreaker:
    instances:
      llm: { sliding-window-size: 20, minimum-number-of-calls: 10, failure-rate-threshold: 50, wait-duration-in-open-state: 30s }
```

`.env.example`：

- 删除 `LLM_BASE_URL`；
- 新增 `LLM_PROVIDER=deepseek`（注释写明：不设置或设为 `none` 时助手关闭）、`DEEPSEEK_BASE_URL`、`MCP_SERVER_URL`、`MCP_CLIENT_ENABLED`、`MCP_DB_PASSWORD`。

CHANGELOG 和 README 里要写明**升级注意**：已有的 `.env` 必须加上 `LLM_PROVIDER=deepseek`，否则助手会被关闭。

#### 没有 `LLM_API_KEY` / `LLM_PROVIDER=none` 时

- `spring.ai.model.chat=none` 时，Spring AI 不会创建 `ChatModel`，也就没有 `ChatClient.Builder` bean。
- **不要**在用户配置类上用 `@ConditionalOnBean(ChatModel.class)`：用户配置先于自动配置处理，这个条件判断不可靠。
- `AssistantService` 的构造函数注入 `ObjectProvider<ChatClient.Builder>`：
  - `getIfAvailable()` 为 null 时，`chatClient` 字段置为 null，启动日志打一行 INFO：“assistant disabled (LLM_PROVIDER=none)”；
  - 否则在构造函数里构建 ChatClient：

```java
this.chatClient = builder.defaultSystem("""
        You are the FTSM Marketplace Assistant for UKM students. Answer only from tool results;
        if a tool returns nothing, say you could not find it. Prices are in RM. Be concise.
        """)
        .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
        .build();
```

- `stream()` 发现 `chatClient == null` 时直接返回 `Flux.just("AI assistant is not configured yet.")`，与现有行为一致。
- 如果 `LLM_PROVIDER=deepseek` 但 `LLM_API_KEY` 为空，启动时是否失败取决于 Spring AI 的校验。实现时要实测：如果会导致启动失败，就在 README 里写明“设置 deepseek 时必须提供 key”；如果不会，第一次调用会失败并走熔断降级。两种情况都要在 PR 里说明实测结果。

`ChatMemory` 使用 Spring AI 自动配置的 `MessageWindowChatMemory`（内存存储），窗口大小 10。ChatMemory 的自动配置不依赖 ChatModel。如果 `LLM_PROVIDER=none` 时它不存在，就用 `ObjectProvider<ChatMemory>` 获取，取不到时不加记忆 advisor。

#### MCP server 不可用时：`McpToolsProvider`

```java
@Component
public class McpToolsProvider {
    // State: client (nullable), lastFailure (Instant)
    /** Never throws. Returns MCP tool callbacks, or an empty array if the server is unreachable. */
    public ToolCallback[] currentTools() {
        if (!enabled) return new ToolCallback[0];
        if (client == null && (lastFailure == null || lastFailure.plus(retryInterval).isBefore(Instant.now()))) {
            try {
                McpSyncClient c = McpClient.sync(HttpClientStreamableHttpTransport.builder(mcpUrl).build())
                        .requestTimeout(Duration.ofSeconds(20)).build();
                c.initialize();
                client = c;
            } catch (Exception e) {
                lastFailure = Instant.now();
                log.warn("[Assistant] MCP server unavailable at {}: {}", mcpUrl, e.toString());
            }
        }
        if (client == null) return new ToolCallback[0];
        try {
            return new SyncMcpToolCallbackProvider(client).getToolCallbacks();
        } catch (Exception e) {      // server went away after init
            closeQuietly(client); client = null; lastFailure = Instant.now();
            return new ToolCallback[0];
        }
    }
    public boolean available() { ... }
}
```

- 类名以 Spring AI 1.1.8 / MCP Java SDK 的实际 API 为准（`io.modelcontextprotocol.client.McpClient`、`HttpClientStreamableHttpTransport`、`org.springframework.ai.mcp.SyncMcpToolCallbackProvider`）。
- `currentTools()` 必须做到：任何异常都不向外抛出，启动阶段不连接 MCP server，失败后按 `mcp-retry-interval` 重试。
- MCP 不可用时，`AssistantService` 在 user 消息之前追加一条 system 提示：“Catalogue tools are temporarily unavailable; tell the user you cannot look up products right now.”，让模型如实告知用户，而不是编造商品信息。

#### `OrderTools` 与 `ToolCallRecorder`

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

本地工具不能用 `.tools(orderTools)` 直接传给 ChatClient，否则绕过了记录器。要先转换成回调，和 MCP 工具合并后一起包装：

```java
ToolCallback[] local = ToolCallbacks.from(orderTools);        // org.springframework.ai.support.ToolCallbacks
ToolCallback[] all = concat(mcpToolsProvider.currentTools(), local);
... .toolCallbacks(recorder.wrap(all))
```

`ToolCallRecorder.wrap` 给每个回调包一层委托：在 `call(input, toolContext)` 里，以 `toolContext` 中的 `conversationId` 为键，记录 `(toolName, 时间)`，存进 LRU（最多 1000 个会话）；`getToolDefinition()` 等方法原样委托。因此**记录范围 = 全部工具**，包括 `my_orders`。评测集里的 `expected_tools` 可以包含 `my_orders`。

调试接口 `GET /api/assistant/debug/tool-calls?conversationId=` 只在 `dev` profile 下注册（`@Profile("dev")` 的 controller），并且只返回当前用户自己会话的记录（`conversationId` 必须以 `<当前 userId>:` 开头）。

#### `AssistantService`

```java
@RateLimiter(name = "llm")
@CircuitBreaker(name = "llm", fallbackMethod = "fallback")
public Flux<String> stream(Long userId, String conversationId, String message) {
    if (chatClient == null) return Flux.just("AI assistant is not configured yet.");
    String convId = userId + ":" + conversationId;
    var spec = chatClient.prompt();
    if (!mcpToolsProvider.available()) spec = spec.system(MCP_UNAVAILABLE_NOTE);
    return spec.user(message)
            .toolCallbacks(recorder.wrap(concat(mcpToolsProvider.currentTools(), ToolCallbacks.from(orderTools))))
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

`ChatbotController`（保留 `POST /api/chat`，返回值不变）：`assistantService.stream(...).collectList().map(parts -> String.join("", parts)).block(Duration.ofSeconds(60))`，用户 ID 从 `@AuthenticationPrincipal` 取。

#### 异步分派的鉴权：保存 SecurityContext，不做宽泛放行

**现状与原因**：`JwtAuthFilter` 继承自 `OncePerRequestFilter`，默认跳过 ASYNC 分派；它把认证信息放进了 `SecurityContextHolder`，却没有存进 `SecurityContextRepository`。controller 返回 `Mono` 或 `Flux` 时，Servlet 会再做一次 ASYNC 分派。Spring Security 6 默认对所有分派类型都做授权，而 ASYNC 分派时 `SecurityContextHolderFilter` 从 repository 里加载不到认证信息，于是被 `anyRequest().authenticated()` 拦成 403。v0.4.0 把 `ChatbotController` 改成阻塞调用，原因就在这里。

**做法**（**不要**用 `dispatcherTypeMatchers(ASYNC).permitAll()`）：

```java
// JwtAuthFilter
private final SecurityContextRepository contextRepository = new RequestAttributeSecurityContextRepository();
...
SecurityContext context = SecurityContextHolder.createEmptyContext();
context.setAuthentication(auth);
SecurityContextHolder.setContext(context);
contextRepository.saveContext(context, request, response);   // ASYNC dispatch reloads it from the request attribute
```

`SecurityConfig` 显式声明同一种 repository，让加载和保存用的是同一个：

```java
.securityContext(sc -> sc.securityContextRepository(new RequestAttributeSecurityContextRepository()))
```

这样，ASYNC 分派仍然要经过 `authenticated()` 校验，只是能够拿到首次分派时已经验证过的认证信息。ERROR 分派保持默认行为。

**测试 `AssistantSecurityTest`**（`@WebMvcTest(AssistantController.class)`，`@Import({SecurityConfig.class, JwtAuthFilter.class, JwtUtils.class, CorsConfig.class})`，`@MockitoBean AssistantService` 返回 `Flux.just("a", "b")`）：

1. 不带 token 发 POST：状态码与现有的受保护接口一致（当前为 403），并且 `assistantService` 没有被调用。
2. 带一个由 `JwtUtils` 签发的合法 token：`perform(...)` 后断言 `request().asyncStarted()`；再执行 `mockMvc.perform(asyncDispatch(result))`，状态 200，响应体包含 `event:token` 和 `data:a`。
3. 用伪造签名的 token：与第 1 条相同。
4. 回归：`POST /api/chat` 带合法 token 返回 200。

第 2 条在修复之前应当得到 403。实现时先写这个测试，确认它在修复前确实失败，再做修复，并把修复前后两次的运行结果都贴进 PR。

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

自动化测试（`./mvnw -B verify` 必须包含并通过）：

- [ ] `AssistantSecurityTest` 的 4 条用例，PR 里同时贴出修复前第 2 条失败的输出。
- [ ] `AssistantServiceTest`：
  - `ObjectProvider` 为空（模拟 `LLM_PROVIDER=none`）时，返回 “not configured”；
  - `McpToolsProvider` 指向一个不存在的地址（`http://127.0.0.1:1`）时，`currentTools()` 返回空数组且不抛异常，并且第二次调用在重试间隔内不再尝试连接。
- [ ] `OrderToolsTest`：`ToolContext` 中的 userId 为 A 时，只查询 A 的订单（mock `OrderService`，验证调用参数）；`my_orders` 的工具定义里没有任何参数。
- [ ] 上下文启动测试：在 `LLM_PROVIDER` 不设置的默认配置下，应用上下文能够启动。

手工验收（贴命令和输出）：

- [ ] `MCP_DB_PASSWORD="bad'pass"` 运行只读账号脚本：退出码 1。
- [ ] 不设置任何 LLM 相关环境变量，`docker compose up -d`：backend 健康；`POST /api/chat` 返回 “not configured”。
- [ ] `docker compose stop mcp-server && docker compose restart backend`：backend 健康；助手回答“暂时无法查询商品”一类的内容；日志里有 “MCP server unavailable”。再 `docker compose start mcp-server`，30 秒后工具恢复可用。
- [ ] `npx @modelcontextprotocol/inspector` 连上 `http://localhost:8081`，能列出 5 个工具并逐个调用成功。**人**截图放进 `docs/images/mcp-inspector.png`。
- [ ] 设置 `LLM_PROVIDER=deepseek` 和真实 key，问“FTSM Hoodie 还有货吗”：debug 接口显示调用了 `get_stock`，回答中的数字与数据库一致。
- [ ] `LLM_API_KEY=bad` 连续请求 15 次：`/actuator/circuitbreakers` 中的 `llm` 进入 OPEN，接口立即返回降级文本（actuator 暴露的端点里要加上 `circuitbreakers`）。
- [ ] 通过 nginx（`http://localhost/`）访问时，回答是逐段出现的。
- [ ] `run_assistant_eval.py` 的结果文件已提交（需要真实 key，由人提供；没有 key 时标“未执行”）。

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
- `DemoCatalogSeeder`（`@Profile("demo")`，`CommandLineRunner`）按以下顺序执行，任何一步失败都让应用启动失败（抛 `IllegalStateException`，异常信息写明是哪一条记录、什么原因），不做部分导入：
  1. **前置检查**：`SEED_DEMO_PASSWORD` 未设置或短于 8 个字符时直接失败。
  2. **整体校验 `catalog.json`**，在写任何数据之前完成：
     - 所有 `id` 唯一，商品在 1001–4999 之间，物品在 5001–9999 之间；
     - `category` 属于上面列出的 10 个类目之一；
     - `price > 0`；
     - `condition` 属于 `NEW`、`LIKE_NEW`、`USED`；
     - `sellerKey` 匹配 `^seller(0[1-9]|1[0-9]|20)$`。
  3. **卖家映射**：`sellerKey = sellerNN` 对应邮箱 `sellerNN@siswa.ukm.edu.my`。对 01–20 逐个执行：
     - 用 `UserRepository.existsByEmail` 检查，不存在就创建（`role=STUDENT`、`emailVerified=true`、密码用 `PasswordEncoder` 加密 `SEED_DEMO_PASSWORD`），已存在就复用。
     - 然后用 `findByEmail` 取出真实 `id`，建立 `Map<String sellerKey, Long sellerId>`。
     - 物品的 `seller_id` 一律取自这个映射，**不得**假设卖家 ID 是连续的。
  4. **导入**：放在一个 `@Transactional` 方法里，用 JdbcTemplate 以显式 ID 执行 `INSERT IGNORE INTO products (id, …)` / `INSERT IGNORE INTO items (id, …)`。MySQL 允许向自增列写入显式值；`created_at` 用当前时间。
  5. **重复导入**：
     - 同一个 ID 已存在时 `INSERT IGNORE` 跳过，因此重复启动是幂等的；
     - 日志输出 “inserted X / skipped Y”；
     - 已存在的记录**不会被更新**。要更新内容，先手动删除这些记录（README 里给出 SQL）。
  6. **自增值**：导入完成后执行 `ALTER TABLE products AUTO_INCREMENT = 10000`、`ALTER TABLE items AUTO_INCREMENT = 10000`。MySQL 会自动取 max(当前值, 10000)，所以重复执行也是安全的。
- P5a 的单元测试 `CatalogJsonValidationTest`：对 `data/catalog.json` 执行与第 2 步相同的校验，并用 3 份故意写错的样例（重复 id、未知类目、未知 sellerKey）验证每种错误都会被拒绝。

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

### P5b.0 开工条件（不满足就停止）

1. `git log --format='%h %ad %s' -- eval/queries.jsonl` 显示，人工标注的提交（`eval: label queries`）已经在 `main` 上，并且每条查询的 `relevant` 都不为空（`python3 -c "import json;assert all(json.loads(l)['relevant'] for l in open('eval/queries.jsonl'))"`）。
2. 提示词里写明了 embedding 提供方：`EMBEDDING_PROVIDER=openai` 或 `EMBEDDING_PROVIDER=ollama`。**没有写明就停止并询问，不得自行选择。**
3. 选择 `openai` 时，人会以环境变量形式提供 `OPENAI_API_KEY`；选择 `ollama` 时，环境里要能运行 `ollama/ollama:0.35.1`。没有 key 或无法运行 Ollama 时，可以完成代码和单元测试，但跑分标为“未执行”，PR 保持 draft。

### P5b.1 Embedding 提供方（二选一，由人在提示词里指定）

| 方案 | 依赖 | 配置 |
| --- | --- | --- |
| OpenAI | `spring-ai-starter-model-openai`（Spring AI BOM 1.1.8） | `spring.ai.model.embedding=${EMBEDDING_PROVIDER:none}`，`spring.ai.model.chat=${LLM_PROVIDER:none}`（明确指定，防止 OpenAI 的 chat 模型被自动配置），`spring.ai.openai.api-key=${OPENAI_API_KEY}`，`spring.ai.openai.embedding.options.model=text-embedding-3-small` |
| Ollama 本地 | `spring-ai-starter-model-ollama`（Spring AI BOM 1.1.8） | `spring.ai.model.embedding=${EMBEDDING_PROVIDER:none}`，`spring.ai.ollama.base-url=${OLLAMA_BASE_URL:http://localhost:11434}`，`spring.ai.ollama.embedding.options.model=bge-m3`；compose 新增 `ollama/ollama:0.35.1` 服务，并在启动时 `ollama pull bge-m3` |

`EMBEDDING_PROVIDER` 默认为 `none`：不配置时没有 `EmbeddingModel`，应用照常启动，向量相关的模式不可用（见 P5b.3）。

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

API 名称以 Spring AI 1.1.8 为准。Spring Boot 的 Redis 自动配置用的是 Lettuce，这里单独建的 Jedis 连接与它并存。

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

- [ ] **自动化**：`NoEmbeddingStartupTest`。
  - 配置：`EMBEDDING_PROVIDER` 不设置，`spring.ai.model.embedding=none`、`spring.ai.model.chat=none`、`app.assistant.mcp-enabled=false`。
  - 断言：应用上下文能够启动；`VectorStore` bean 不存在；`HybridSearchService` 的 `KEYWORD` 模式能正常返回结果，`VECTOR` / `RRF` / `RRF_RERANK` 模式抛 `BusinessException`；`SearchController` 不传 mode 时退回 `KEYWORD`。
  - 实现方式：P6a 已合并时，继承 `AbstractIntegrationTest`；否则在 `HybridSearchService` 层用 mock 的 `ObjectProvider` 做单元测试，并另写一个只加载搜索相关 bean 的上下文测试。
- [ ] **手工**：用 compose 启动、不设置 `EMBEDDING_PROVIDER`，backend 和 mcp-server 都健康，`GET /api/search?q=hoodie` 返回 200。
- [ ] `app.catalog.reindex=true` 启动后，`FT.INFO idx:catalog` 的文档数 = 商品数 + ACTIVE 物品数。
- [ ] 在管理后台修改一个商品的名称，几秒内能用新名称的同义词搜到它。
- [ ] `run_retrieval_eval.py --modes keyword,vector,rrf,rrf_rerank` 的结果文件已提交，包含总表和分类型表；README 的评测表与结果文件一致。
- [ ] MCP 的 `search_products` 返回的是 RRF 结果。
- [ ] 结果不如预期时如实记录，**不要为了提高分数去修改评测集**。

---

## P6a · Testcontainers 集成测试 + GitHub Actions

前置：P2、P4a 都已合并（使用根目录 Wrapper，命令都在仓库根执行）。P4b、P5 未合并时，相应的测试和镜像跳过，并在 PR 里注明。

### P6a.1 依赖（backend `pom.xml`，test scope）

全部由 Boot 3.5.16 的 BOM 管理版本，**不写版本号**：

| artifact | Boot 3.5.16 管理的版本 | 用到的类 |
| --- | --- | --- |
| `org.springframework.boot:spring-boot-testcontainers` | 3.5.16 | `@ServiceConnection` |
| `org.testcontainers:junit-jupiter` | 1.21.4 | — |
| `org.testcontainers:mysql` | 1.21.4 | `org.testcontainers.containers.MySQLContainer` |
| `org.testcontainers:kafka` | 1.21.4 | `org.testcontainers.kafka.KafkaContainer`（面向 `apache/kafka` 镜像。**不要用**已废弃的 `org.testcontainers.containers.KafkaContainer`，它面向 Confluent 镜像） |
| `com.redis:testcontainers-redis` | 2.2.4 | `com.redis.testcontainers.RedisContainer`（Boot 3.5 的 `@ServiceConnection` 原生支持它） |
| `org.awaitility:awaitility` | 4.2.2 | — |

注意：Testcontainers 2.x 改了 artifact 名（例如 `testcontainers-kafka`）和包名。Boot 3.5 管理的是 1.21.x，不要混用 2.x。

在 `backend/pom.xml` 的 `<build>` 里加 `maven-failsafe-plugin`（Boot 管理版本 3.5.6），执行 `integration-test` 和 `verify` 两个 goal，让 `*IT` 测试在 `verify` 阶段运行。

### P6a.2 测试基础设施

```java
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {
    @Bean @ServiceConnection
    MySQLContainer<?> mysql() { return new MySQLContainer<>(DockerImageName.parse("mysql:8.0.46")); }

    @Bean @ServiceConnection
    KafkaContainer kafka() { return new KafkaContainer(DockerImageName.parse("apache/kafka:3.9.2")); }   // org.testcontainers.kafka.KafkaContainer

    @Bean @ServiceConnection
    RedisContainer redis() { return new RedisContainer(DockerImageName.parse("redis:8.10.2")); }        // com.redis.testcontainers.RedisContainer
}

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.ai.model.chat=none", "spring.ai.model.embedding=none", "app.assistant.mcp-enabled=false",
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
    runs-on: ubuntu-24.04
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
    runs-on: ubuntu-24.04
    defaults: { run: { working-directory: frontend } }
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with: { node-version: '20.20.2', cache: npm, cache-dependency-path: frontend/package-lock.json }
      - run: npm ci
      - run: npm run build
  images:
    needs: [backend, frontend]
    if: github.event_name == 'push' && github.ref == 'refs/heads/main'
    runs-on: ubuntu-24.04
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

- 依赖：`net.javacrumbs.shedlock:shedlock-spring:6.10.0`、`net.javacrumbs.shedlock:shedlock-provider-jdbc-template:6.10.0`（在根 pom 用 `<shedlock.version>` 属性统一管理）。**不要用 7.x**，它面向 Spring Framework 7 / Boot 4。
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
# 文件第一行必须是 `# syntax=docker/dockerfile:1.7`（ADD --checksum 需要 BuildKit 的 Dockerfile 1.6 及以上语法）
ADD --checksum=sha256:f787eb6c7f3d18e69a431e108a15278d25ee37f83d68b678f621e063f3988f82 \
    https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.32.0/opentelemetry-javaagent.jar \
    /otel/opentelemetry-javaagent.jar
```

agent 只在设置了环境变量时启用：compose / K8s 中设置 `JAVA_TOOL_OPTIONS=-javaagent:/otel/opentelemetry-javaagent.jar`，并设置 `OTEL_SERVICE_NAME`、`OTEL_EXPORTER_OTLP_ENDPOINT=http://lgtm:4318`、`OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf`。agent 自带的 Micrometer 桥会把 Micrometer 指标一并通过 OTLP 发出，Grafana 直接查询即可。

**跨 outbox 的 trace 衔接**（否则 HTTP 请求和 Kafka 消费会是两条互不相连的 trace）：

- 依赖：`io.opentelemetry:opentelemetry-api`（版本由 Boot 3.5.16 的 OpenTelemetry BOM 管理；agent 会桥接这个 API）。
- `V5__outbox_traceparent.sql`：`ALTER TABLE order_outbox ADD COLUMN traceparent VARCHAR(64) NULL;`
- `SeckillService.buy` 写 outbox 时，用 `W3CTraceContextPropagator.getInstance().inject(Context.current(), map, Map::put)` 取出 `traceparent`，一起写入该列。
- `OutboxPublisher` 发送每一行之前，先 `extract` 出 Context 并 `makeCurrent()`，然后在这个 scope 内执行 `kafka.send(...)`。这样 agent 生成的 producer span 会挂在原 HTTP trace 之下，并把上下文注入消息头，消费端自动延续。
- `OutboxDao.lockBatch` 的查询要带上 `traceparent` 列。

`docker-compose.yml` 新增：

```yaml
  lgtm:
    image: grafana/otel-lgtm:0.35.0
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
| `KAFKA_BOOTSTRAP` | P2 | `localhost:9092` | compose 内为 `kafka:9092`，宿主机上为 `localhost:29092` |
| `KAFKA_REPLICAS` | P2 | `1` | |
| `SECKILL_MODE` | P3 | `async` | 仅压测时使用 `sync` |
| `LLM_PROVIDER` | P4b | `none` | 设为 `deepseek` 才启用助手（升级时已有的 `.env` 需要补上） |
| `DEEPSEEK_BASE_URL` | P4b | `https://api.deepseek.com` | 取代 `LLM_BASE_URL` |
| `MCP_SERVER_URL` | P4b | `http://localhost:8081` | |
| `MCP_CLIENT_ENABLED` | P4b | `true` | |
| `MCP_DB_USERNAME` / `MCP_DB_PASSWORD` | P4b | `ftsm_ro` / — | 只读账号 |
| `SEED_DEMO_PASSWORD` | P5a | — | 演示卖家账号 |
| `EMBEDDING_PROVIDER` | P5b | `none` | `openai` 或 `ollama`，由人决定 |
| `OPENAI_API_KEY` | P5b / P7 | — | |
| `OLLAMA_BASE_URL` | P5b | `http://localhost:11434` | 选择 Ollama 方案时 |
| `OPENAI_VISION_MODEL` | P7 | `gpt-4o` | |
| `OTEL_*`、`JAVA_TOOL_OPTIONS` | P6b | 未设置（不启用 agent） | |

k6 脚本的输入变量（`BASE_URL`、`EVENT_ID`、`JWT_SECRET`、`RATE`、`RAMP_SECONDS`、`STEADY_SECONDS`、`BUYERS`、`STOCK`、`VUS`、`REJECT_STATUS`、`SUMMARY_PATH`）只对压测脚本生效，不是应用的配置项，见 §P0.6。

---

## 附 B · P0 执行 prompt

把下面整段交给执行 P0 的 agent：

```text
你在仓库 feniturema/FYP 上执行升级规格的 P0 阶段。只做文档里写明的事。

开始前：
1. 读 docs/CHANGE_SPEC.md 的 §0（全部）和 §P0（全部），再读 HANDOFF.md §4 的代码约定。
2. 确认前置条件：origin/main 上存在 docs/CHANGE_SPEC.md，且 CHANGELOG.md 含 v0.4.5。不满足就停止并报告，不要自己合并任何分支。
3. git status --porcelain 必须为空；不为空就停止并报告，不要 stash、reset 或删除任何改动。
4. 从最新的 origin/main 拉出你的工作分支。

执行：
- 按 §P0.3 → §P0.4 → §P0.5 → §P0.6 的顺序实现，文件范围以 §P0.7 为准；§P0.7 列出的“不得修改”的文件一个都不能动。
- V1__baseline.sql 只能由 scripts/db/export_baseline_schema.sh 从真实 MySQL 导出；不能手写，也不能用 H2 生成。
- 没有 Docker 时，按 §P0.2 的替代方式安装 MySQL 8.0.46 和 k6 2.3.0；替代方式也不可用时，按 §P0.2 的表格决定哪些步骤继续、哪些停止。
- 压测只跑 §P0.6 规定的冒烟规模（RATE=10、BUYERS=20、STOCK=5），结果放 loadtest/results/_smoke/。不要跑正式压测，也不要把冒烟数字写成性能结论。

验收：
- 逐条执行 §P0.8 的 17 项。PR 描述里给每一项写明：执行的命令、关键输出、结论（通过 / 失败 / 未执行及原因）。
- 遇到 §0.8 或 §P0.9 列出的情况，立即停止相关步骤并报告，不得绕过（不得删除阈值、改断言、手改 V1、关闭 checksum 校验、换用未固定的版本）。

收尾：
- 更新 CHANGELOG.md（v0.5.0）、HANDOFF.md、README.md，内容按 §P0.7；CHANGELOG 里列出所有“未执行”的验收项。
- 一个 PR，标题 “P0: baseline tag, Maven Wrapper, Flyway V1, k6 smoke (v0.5.0)”。有任何验收项未执行或失败时，PR 设为 draft。
- 清理临时容器和 worktree。
```
