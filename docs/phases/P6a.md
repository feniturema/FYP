# P6a · Testcontainers 集成测试 + GitHub Actions CI

> 版本：v0.9.0 · 前置：P2、P4a · 下一阶段：P4b（P4b、P5a、P5b、P6b 的集成测试都建立在本阶段的基础设施上）
> 全局约定见 [`../CHANGE_SPEC.md`](../CHANGE_SPEC.md) §0。证据标记含义同 P0：**[实验]** / **[源码]** / **[未验证]**。

## 1. 目标与非目标

**问题**：P2 的正确性保证（不超卖、幂等、故障恢复）目前只能在 Compose 里手工验收；仓库也没有 CI。

| | 实施前 | 实施后 |
| --- | --- | --- |
| 集成测试 | 无 | `backend/src/test/java/.../it/*IT.java`，由 Failsafe 执行，使用真实的 MySQL 8.0.46、Kafka 3.9.2、Redis 8.10.2 |
| 零测试保护 | 无 | `scripts/ci/check_test_reports.py` 对照 `scripts/ci/expected-tests.json` 检查 |
| CI | 无 | PR：构建、单元测试、IT、前端构建、镜像构建（不推送）；main：推送镜像到 GHCR |

**非目标**：
- 为 P4b / P5 写测试：这些阶段在本阶段之后实施，各自负责新增 IT，并更新 `expected-tests.json`；
- K8s 和可观测性（P6b）；
- 修改任何业务代码。例外：如果 IT 暴露出 P2 的缺陷，**停止**，并报告给人，由人决定是先修 P2 还是在本 PR 中修复。

## 2. 前置条件

| 类别 | 要求 | 缺失时 |
| --- | --- | --- |
| 已合并阶段 | P2、P4a（使用根目录的 Wrapper） | 停止 |
| 工具 | 本地 Docker ≥ 24（Testcontainers 需要）；JDK 21 | 没有 Docker 时：代码可以写完，但 IT 无法执行，PR 保持 draft；CI 上的运行可以作为证据 |
| GitHub | Actions 已启用；**人工**：仓库设置 → Actions → Workflow permissions 允许读写，或者至少允许 `packages: write` | 发布到 GHCR 的那一步在合并后才会执行；缺少权限不阻塞本 PR，作为外部待办 |
| 资源 | 本地内存 ≥ 6 GB | — |
| 凭据 | 不需要（GHCR 用的是 `GITHUB_TOKEN`） | — |

## 3. 阶段输入与输出

**输入**：P2 的全部类（`SeckillService`、`OutboxDao`、`OutboxPublisher`、`SeckillOrderListener`、`SeckillOrderWriter`、`SeckillReconciler`、`OrderService`）；多模块结构。

**输出与契约**（后续阶段只能扩展，不能削弱）：

| 输出 | 契约 |
| --- | --- |
| `…/it/TestcontainersConfiguration` | 3 个容器 bean，后续阶段不得修改镜像版本；需要新容器（例如 P5b 的 Ollama）时，在**各自的**测试配置里添加 |
| `…/it/AbstractIntegrationTest` | 公共 fixture 方法（§6.3）；后续阶段可以新增方法，不得改变已有方法的语义 |
| 命名约定 `*IT` | 由 Failsafe 执行；`*Test` 由 Surefire 执行 |
| `scripts/ci/expected-tests.json` | 每个阶段把自己新增的 IT 类名和最少用例数加进去 |
| `.github/workflows/ci.yml` | 镜像矩阵由 `detect` job 根据实际存在的 Dockerfile 动态生成 |

## 4. 逐文件清单

`T=backend/src/test/java/my/edu/ukm/ftsm/ecommerce`

| 操作 | 完整路径 | 职责 | 验证 |
| --- | --- | --- | --- |
| 修改 | `backend/pom.xml` | test scope 新增：`spring-boot-testcontainers`、`org.testcontainers:junit-jupiter`、`org.testcontainers:mysql`、`org.testcontainers:kafka`、`com.redis:testcontainers-redis`、`org.awaitility:awaitility`（版本全部由 Boot 3.5.16 管理：Testcontainers 1.21.4、testcontainers-redis 2.2.4、Awaitility 4.2.2 **[源码]**）；build 新增 `maven-failsafe-plugin`（执行 `integration-test`、`verify` 两个 goal，`<forkCount>1</forkCount>`，`<reuseForks>true</reuseForks>`） | A1 |
| 新增 | `T/it/TestcontainersConfiguration.java` | §6.2 | A1 |
| 新增 | `T/it/AbstractIntegrationTest.java` | §6.3 | A1 |
| 新增 | `T/it/SeckillConcurrencyIT.java` | §8 | A2 |
| 新增 | `T/it/DuplicatePurchaseIT.java` | §8 | A2 |
| 新增 | `T/it/ConsumerRestartIT.java` | §8 | A2 |
| 新增 | `T/it/KafkaOutageIT.java` | §8 | A2 |
| 新增 | `T/it/ReplayIT.java` | §8 | A2 |
| 新增 | `T/it/OutboxCompensationIT.java` | §8 | A2 |
| 新增 | `T/it/DltPublishFailureIT.java` | §8（验证 P2 §6.1 中那条标为 [未验证] 的语义） | A2 |
| 新增 | `T/it/ProductOrderRaceIT.java` | §8 | A2 |
| 新增 | `T/it/ItemOrderRaceIT.java` | §8 | A2 |
| 新增 | `T/it/ReconcilerIT.java` | §8 | A2 |
| 新增 | `T/it/MigrationIT.java` | 断言 Flyway 的历史版本为 `[1, 2]`（以及可能存在的 1.1），并且 `validate` 通过 | A2 |
| 新增 | `scripts/ci/check_test_reports.py` | §6.5 | A3 |
| 新增 | `scripts/ci/expected-tests.json` | §6.5 | A3 |
| 新增 | `.github/workflows/ci.yml` | §6.6 | A4–A6 |
| 修改 | `README.md` | CI 徽章；“Running integration tests”一节 | — |
| 修改 | `HANDOFF.md`、`CHANGELOG.md` | v0.9.0 | — |

**禁止修改**：`backend/src/main/**`（例外见 §1）、迁移文件、`frontend/src/**`。

## 5. 实施顺序

| # | 任务 | 完成条件 | 持久状态 |
| --- | --- | --- | --- |
| 1 | 修改 pom（依赖 + Failsafe） | `./mvnw -B -q -DskipTests verify` 成功 | 文件 |
| 2 | 编写 TestcontainersConfiguration 和 AbstractIntegrationTest；先写 `MigrationIT` | `./mvnw -B verify -Dit.test=MigrationIT` 通过 | 本地 Docker 容器（测试结束后自动删除） |
| 3 | 逐个编写 IT，每写完一个就单独运行 | 各自通过 | 同上 |
| 4 | 编写 check_test_reports 和 expected-tests.json | A3 通过 | 文件 |
| 5 | 完整执行 `./mvnw -B verify` | A1、A2 通过 | — |
| 6 | 编写 ci.yml；推送分支，提 PR | PR 上 CI 全绿（A4、A5） | 远端 |
| 7 | 文档 | — | — |

## 6. 实现契约

### 6.1 测试的隔离规则

| 维度 | 规则 |
| --- | --- |
| 容器 | 声明为测试配置中的 `@Bean`，生命周期跟随 Spring 上下文。相同配置的 IT 类共享同一个上下文（Spring 测试上下文缓存），因此也共享同一组容器。上下文关闭时，Spring 会调用容器 bean 的推断 destroy 方法 `close()` 停止容器（**[未验证]**，由 A2 中 KafkaOutageIT 之后的类能正常启动来证明） |
| `@DirtiesContext` | 只用在 `KafkaOutageIT`、`ReplayIT`、`DltPublishFailureIT`、`ConsumerRestartIT` 这几个会改变 Kafka 状态的类上，并且用 `classMode = AFTER_CLASS`。这样在该类结束后会销毁 Spring 上下文和其中的全部 bean：3 个容器、Kafka 监听容器、调度器、连接池。下一个 IT 类会重新启动一组新的容器（较慢，但状态干净） |
| 并行 | 关闭。JUnit 默认串行；Failsafe 使用 `forkCount=1` |
| 数据 | 每个测试方法都通过 fixture 新建自己的商品和活动（名称带 `UUID` 后缀），所有断言都按 `eventId` 过滤。数据不清理，依靠唯一 ID 实现隔离 |
| 用户 ID | 每个类有自己的 `USER_BASE`（定义在类里的常量，彼此间隔 `10_000_000`，见 §6.4 的表），测试方法内部再用偏移量区分 |
| 调度器 | 测试属性 `app.seckill.relay-interval-ms=50`；`SeckillReconciler` 在测试里注入固定的 `Clock`（只有 ReconcilerIT 需要；其他类不依赖对账结果） |
| 恢复 | 所有 pause、停止监听、删除 topic、spy 的改动，都必须在 `try { … } finally { 恢复 }` 中执行，恢复动作写在 `finally` 里 |

### 6.2 `TestcontainersConfiguration`

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
```

依据 **[源码]**：

- `org.testcontainers.kafka.KafkaContainer` 位于 `org.testcontainers:kafka:1.21.4`；Boot 3.5.16 通过 `ApacheKafkaContainerConnectionDetailsFactory` 为它提供连接信息。
- Boot 的 `RedisContainerConnectionDetailsFactory` 支持 `com.redis.testcontainers.RedisContainer`。
- **不要**使用已废弃的 `org.testcontainers.containers.KafkaContainer`，也不要引入 Testcontainers 2.x 的 artifact（2.x 的 artifact 名和包名都变了）。

### 6.3 `AbstractIntegrationTest`

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.seed.enabled=false", "app.seckill.relay-interval-ms=50", "app.mail.enabled=false",
        "spring.ai.model.chat=none", "spring.ai.chat.client.enabled=false", "spring.ai.model.embedding=none",
        "spring.ai.mcp.client.enabled=false", "app.assistant.mcp-enabled=false"})   // 在 P4b 之前这些属性不会生效，也没有影响
@Import(TestcontainersConfiguration.class)
public abstract class AbstractIntegrationTest {
    @LocalServerPort protected int port;
    protected long createProduct(int totalStock, BigDecimal price);                // 返回 productId
    protected long createActiveEvent(long productId, int stock);                   // start = now-1s, end = now+10min；之后调用 seckillService.reconcileEvents() 完成预热
    protected String tokenFor(long userId);                                        // 用 JwtUtils 和一个只设置了 id/email/role/name 的 User 对象签发（不入库）
    protected HttpResponse<String> buy(String token, long eventId);                // java.net.http.HttpClient，超时 10 s
    protected void awaitOrders(long eventId, long expected, Duration timeout);     // Awaitility，按 seckill_event_id 计数
    protected long countOrders(long eventId);
    protected long outboxPending(long eventId);
    protected String redisGet(String key);
}
```

### 6.4 各 IT 的数据与断言

| 类 | `USER_BASE` | 数据准备 | 动作 | 断言 | 恢复 / 清理 |
| --- | --- | --- | --- | --- | --- |
| `MigrationIT` | — | — | 查询 `flyway_schema_history` | 版本为 `[1, 2]`（以及可能存在的 1.1），success 全部为 1 | — |
| `SeckillConcurrencyIT` | 10,000,000 | 商品 1，活动库存 100 | 用 `Executors.newVirtualThreadPerTaskExecutor()` 发 2000 个不同用户的购买请求 | 202 恰好 100 个，其余全部 409；`awaitOrders(…,100,60s)`；`COUNT(DISTINCT buyer_id)=100`；`sold_count=100`；Redis 库存为 `"0"`；outbox 待发送为 0 | 无 |
| `DuplicatePurchaseIT` | 20,000,000 | 活动库存 10 | 同一用户并发请求 20 次 | 恰好 1 个 202、19 个 409（`ALREADY_BOUGHT`）；订单数为 1 | 无 |
| `ConsumerRestartIT` | 30,000,000 | 活动库存 200 | 并发发 200 个请求；第 50 个请求返回时，`registry.getListenerContainers().forEach(stop)`；2 s 后再全部 `start` | 订单数 = 202 的数量 = 200。**注意**：这里验证的是优雅停止，不是进程崩溃；崩溃由 P2 的 A6 验证 | `finally` 中全部 `start`；`@DirtiesContext` |
| `KafkaOutageIT` | 40,000,000 | 活动库存 20 | `DockerClientFactory.instance().client().pauseContainerCmd(kafka.getContainerId()).exec()`；下单 20 笔；10 s 后 unpause | 暂停期间 20 笔都返回 202，outbox 待发送数 > 0；unpause 后 60 s 内订单数达到 20 | `finally` 中 unpause；`@DirtiesContext` |
| `ReplayIT` | 50,000,000 | 活动库存 30，下单 30 笔，等全部落单 | 停止监听容器；轮询 `AdminClient.describeConsumerGroups(List.of("seckill-order-writer"))`，直到 state 为 `EMPTY`（最多 60 s）；然后 `alterConsumerGroupOffsets` 把所有分区重置为 0；再 `start` | 30 s 后订单数仍为 30；`existsByTrackingToken` 路径至少走过一次（用 `@MockitoSpyBean SeckillOrderWriter` 验证） | `finally` 中 `start`；`@DirtiesContext` |
| `OutboxCompensationIT` | 60,000,000 | 活动库存 5 | `@MockitoSpyBean OutboxDao`：`insert` 抛出 `DataAccessResourceFailureException`，`existsByOrderIdSafely` 返回 false | 返回 503；Redis 库存仍为 `"5"`；bought 集合里没有这个用户；该用户第二次购买（spy 已恢复正常）返回 202 | `finally` 中 `Mockito.reset(spy)` |
| `DltPublishFailureIT` | 70,000,000 | — | 用 AdminClient 删除 `seckill.orders.DLT`；直接用 `KafkaTemplate` 向 `seckill.orders` 发一条非法 JSON；等 20 s | 计数器 `seckill.dlt.publish.failed` ≥ 1；消费组在该分区上提交的 offset **小于**这条毒消息的 offset。然后重建 DLT topic，60 s 内 DLT 中出现这条消息，并且 offset 越过它 | `finally` 中重建 DLT；`@DirtiesContext` |
| `ProductOrderRaceIT` | 80,000,000 | 普通商品，库存 1 | 50 个用户（需要在 `users` 表中建真实记录）并发 `POST /api/orders` | 恰好 1 个 200；库存为 0；订单数为 1 | 无 |
| `ItemOrderRaceIT` | 90,000,000 | 一个 C2C 物品 | 20 个用户并发下单 | 恰好 1 个 200；状态为 SOLD | 无 |
| `ReconcilerIT` | 100,000,000 | 一个已结束的活动，5 张订单 | 用固定 `Clock` 把时间设为 endTime + 3 分钟，然后直接调用 `reconciler.run()` | `reconciled = 1`；mismatch 计数器为 0。第二个用例：手工把 Redis 库存改成错误的值，期望 mismatch 计数器为 1 | 无 |

`DltPublishFailureIT` 的结论回填到 P2 §6.1：如果实际行为与 P2 的描述不符（例如 offset 被提交了），**停止**并报告，由人决定修改 P2 的实现还是修改 P2 的规格。

### 6.5 零测试保护

`scripts/ci/expected-tests.json`（P6a 版本）：

```json
{"failsafe": {"MigrationIT":1, "SeckillConcurrencyIT":1, "DuplicatePurchaseIT":1, "ConsumerRestartIT":1, "KafkaOutageIT":1,
              "ReplayIT":1, "OutboxCompensationIT":1, "DltPublishFailureIT":1, "ProductOrderRaceIT":1, "ItemOrderRaceIT":1,
              "ReconcilerIT":2},
 "surefire_min_total": "<P6a 开始时在仓库根目录执行 ./mvnw -B verify 得到的 Surefire 实际总数>"}
```

`check_test_reports.py`：

- 解析 `backend/target/failsafe-reports/TEST-*.xml` 和 `backend/target/surefire-reports/TEST-*.xml`。
- 对 `failsafe` 中的每个类，要求：报告存在、`tests ≥` 期望值、`failures = errors = skipped = 0`。
- Surefire 的总数要求 `≥ surefire_min_total`，并且 skipped = 0。`surefire_min_total` 必须填一个整数：本阶段开始时实际跑出来的数，而不是估计值。
- 任何一条不满足，退出码 1，并打印汇总表（类名、tests、failures、errors、skipped）。

后续阶段的义务：P4b、P5a、P5b、P6b 都必须把自己新增的 IT 加进这个 JSON。**禁止**用 `@Disabled` 或 `assumeTrue` 跳过测试：skipped 不为 0 即判定失败。

### 6.6 `.github/workflows/ci.yml`

```yaml
name: ci
on:
  pull_request:
  push:
    branches: [main]
permissions:
  contents: read
jobs:
  backend:
    runs-on: ubuntu-24.04
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '21', cache: maven }
      - run: ./mvnw -B verify
      - run: python3 scripts/ci/check_test_reports.py --expect scripts/ci/expected-tests.json
      - uses: actions/upload-artifact@v4
        if: always()
        with: { name: test-reports, path: '**/target/*-reports/' }
  frontend:
    runs-on: ubuntu-24.04
    defaults: { run: { working-directory: frontend } }
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with: { node-version: '20.20.2', cache: npm, cache-dependency-path: frontend/package-lock.json }
      - run: npm ci
      - run: npm run build
  detect:
    runs-on: ubuntu-24.04
    outputs: { matrix: ${{ steps.m.outputs.matrix }} }
    steps:
      - uses: actions/checkout@v4
      - id: m
        run: |
          python3 - <<'PY' >> "$GITHUB_OUTPUT"
          import json, os
          svcs = [("backend",".","backend/Dockerfile"),("mcp-server",".","mcp-server/Dockerfile"),("frontend","frontend","frontend/Dockerfile")]
          inc = [{"name":n,"context":c,"file":f} for n,c,f in svcs if os.path.exists(f)]
          print("matrix=" + json.dumps({"include": inc}))
          PY
  images-build:            # PR：只构建，不推送，没有写权限
    if: github.event_name == 'pull_request'
    needs: [backend, frontend, detect]
    runs-on: ubuntu-24.04
    strategy: { matrix: ${{ fromJSON(needs.detect.outputs.matrix) }} }
    steps:
      - uses: actions/checkout@v4
      - uses: docker/setup-buildx-action@v3
      - uses: docker/build-push-action@v6
        with: { context: '${{ matrix.context }}', file: '${{ matrix.file }}', push: false, cache-from: type=gha, cache-to: 'type=gha,mode=max' }
  images-publish:          # main：推送；只有这个 job 拥有 packages: write
    if: github.event_name == 'push' && github.ref == 'refs/heads/main'
    needs: [backend, frontend, detect]
    runs-on: ubuntu-24.04
    permissions: { contents: read, packages: write }
    strategy: { matrix: ${{ fromJSON(needs.detect.outputs.matrix) }} }
    steps:
      - uses: actions/checkout@v4
      - id: owner
        run: echo "lc=${GITHUB_REPOSITORY_OWNER,,}" >> "$GITHUB_OUTPUT"
      - uses: docker/setup-buildx-action@v3
      - uses: docker/login-action@v3
        with: { registry: ghcr.io, username: '${{ github.actor }}', password: '${{ secrets.GITHUB_TOKEN }}' }
      - uses: docker/build-push-action@v6
        with:
          context: '${{ matrix.context }}'
          file: '${{ matrix.file }}'
          push: true
          tags: ghcr.io/${{ steps.owner.outputs.lc }}/ftsm-${{ matrix.name }}:${{ github.sha }}
          cache-from: type=gha
          cache-to: type=gha,mode=max
```

镜像数量等于实际存在的 Dockerfile 数量：P6a 时是 backend 和 frontend 共 2 个；P4b 合并之后变成 3 个，不需要再修改 workflow。

## 7. 配置与运行

- 不新增应用配置项。
- 本地运行（工作目录 `$REPO`）：

  ```bash
  ./mvnw -B verify
  python3 scripts/ci/check_test_reports.py --expect scripts/ci/expected-tests.json
  ```

- Testcontainers 的地址由 `@ServiceConnection` 注入，不需要手写主机名和端口。
- 每个 IT 的超时：Awaitility 最长 60 s；Failsafe 的 `forkedProcessTimeoutInSeconds=1800`。

## 8. 测试清单

即 §6.4 中的 11 个 IT（`ReconcilerIT` 有 2 个用例）。全部是确定性测试，不依赖 AI。

## 9. 验收矩阵

| 编号 | 验收目标 | 前置/fixture | 工作目录 | 完整命令 | 预期断言 | 证据 | 自动/人工 | 阻塞 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| A1 | 构建与单元测试 | Docker | `$REPO` | `./mvnw -B verify` | BUILD SUCCESS | PR | 自动 | 是 |
| A2 | 全部 IT 执行且通过 | A1 | `$REPO` | 已包含在 A1 中 | failsafe-reports 中有 11 个类，`tests ≥ 12`，`failures = errors = skipped = 0` | `backend/target/failsafe-reports` 的汇总，贴进 PR | 自动 | 是 |
| A3 | 零测试保护有效 | A1 | `$REPO` | 先运行 `python3 scripts/ci/check_test_reports.py --expect scripts/ci/expected-tests.json`（期望退出码 0）；再删掉一个 failsafe 报告文件后重跑（期望退出码 1） | 两次的退出码分别为 0 和 1 | PR | 自动 | 是 |
| A4 | PR 上的 CI | 推送分支 | GitHub | PR 的 Checks 页面 | `backend`、`frontend`、`detect`、`images-build`（2 个）全部成功；`images-publish` 显示为 skipped | PR 中的 Checks 链接 | 自动 | 是 |
| A5 | 权限最小化 | — | `$REPO` | `python3 -c "import yaml;d=yaml.safe_load(open('.github/workflows/ci.yml'));print(d['permissions'], d['jobs']['images-publish']['permissions'])"` | 顶层为 `{'contents': 'read'}`；只有 `images-publish` 拥有 `packages: write` | PR | 自动 | 是 |
| A6 | 合并后发布镜像 | 合并到 main 之后 | GitHub | main 分支上这次 push 的 workflow run | `images-publish` 成功；GHCR 中出现 2 个 tag 为该 sha 的镜像 | 合并后在 HANDOFF 里记录 | 人工 | **否**（合并后才能验证，作为外部待办） |

## 10. 升级、重跑与恢复

- 没有数据变更。IT 使用一次性容器；Ryuk 会在 JVM 退出后清理残留的容器。
- 重跑是安全的。如果本地存在残留的 `testcontainers` 容器，只能用 `docker ps -a --filter label=org.testcontainers=true` 找出它们再删除，不得删除别的容器。
- CI 失败时：修复后推送新的 commit。**不得**用空 commit 重新触发 CI，也不得关闭再重开 PR。

## 11. 完成判定与 PR

- **代码完成，可以合并**：A1–A5 全部通过。
- **draft**：任一项失败；本地没有 Docker 且 CI 尚未通过；`DltPublishFailureIT` 揭示的行为与 P2 的描述不符。
- **合并后**：A6，由人在合并后核对；失败（例如缺少写 packages 的权限）时按 §2 调整仓库设置。这**不影响**本 PR 的完成判定。
- **部署验收**：属于 P6b。
- **标题**：`P6a: Testcontainers integration tests and CI (v0.9.0)`
- **描述**：沿用 P0 的模板，另加 “IT report table (class, tests, failures, errors, skipped)”。

## 12. 独立 agent prompt

见 [`../agent-prompts/P6a.md`](../agent-prompts/P6a.md)。
