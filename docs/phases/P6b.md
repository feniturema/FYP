# P6b · ShedLock、健康探针、OpenTelemetry、Grafana、Kubernetes（kind）

> 版本：v0.13.0 · 前置：P6a、P4b、P5a（必须先有 V3，才能新增 V4、V5）；P5b 建议先完成，但不强制 · 下一阶段：P7（可选）
> 全局约定见 [`../CHANGE_SPEC.md`](../CHANGE_SPEC.md) §0。证据标记含义同 P0：**[实验]** / **[源码]** / **[未验证]**。

## 1. 目标与非目标

**问题**：

- 定时任务在每个副本上都会运行（B3：可能重复预热，导致库存被重置）；
- 没有 liveness / readiness 探针；
- 一次下单跨越 HTTP → outbox → Kafka → 消费者，但这条链路看不见；
- 没有可部署到集群的清单。

| | 实施前 | 实施后 |
| --- | --- | --- |
| 定时任务 | 每个副本都运行 | ShedLock：`reconcileEvents`、`SeckillReconciler`、`OutboxJanitor`、`CatalogIndexer` 的对账（P5b 已合并时）同一时刻只在一个副本上运行；`OutboxRelay` 不加锁 |
| 健康检查 | 只有 `/actuator/health` | `/actuator/health/liveness` 和 `/actuator/health/readiness`，允许匿名访问 |
| 链路追踪 | 无 | OTel Java agent 2.32.0；traceparent 存在 outbox 中，使 Kafka 的生产者 span 能挂回原来的 HTTP trace |
| 指标 | 无 | Micrometer OTLP registry 推送到 `grafana/otel-lgtm:0.35.0`；6 个面板 |
| 部署 | Compose | `k8s/`（kustomize），在 kind v0.33.0 上验收 |

**非目标**：

- 生产级 Kafka 和 MySQL（都是单副本）；
- 跨节点共享上传文件（只说明限制）；
- Ingress 和 TLS；
- 改变任何业务语义。

## 2. 前置条件

| 类别 | 要求 | 缺失时 |
| --- | --- | --- |
| 已合并阶段 | P6a、P4b、P5a；`flyway_schema_history` 中最高版本为 3 | 停止（否则新增的 V4 会排在一个尚未执行的 V3 之前，`out-of-order=false` 时 Flyway 会拒绝） |
| 工具 | Docker；kind v0.33.0（`kind-linux-amd64` 的 sha256 为 `aee6151561422756b764a4ae28e7f44cda5af5a9eead3cc9985112b1de8d8e0d`）；kubectl v1.37.1（sha256 为 `65691ff77eb6fa44c908b77a1082c9f092c3b9733b5cefabec0d1104890e21a8`）；kubeconform v0.8.0（`linux-amd64.tar.gz` 的 sha256 为 `9bc2bffbf71f261128533edaf912153948b7ff238f9a531ae6d34466ec287883`）**[源码：Release 文件]** | 不能创建 kind 集群时：清单的静态校验（A9）照常执行，真实集群的验收（A10–A14）标“未执行”，PR 保持 draft |
| 资源 | kind 集群：≥ 4 CPU、≥ 8 GB 内存可分配 | HPA 扩容验收可能无法完成（§9 A14 的判定规则） |
| 端口 | 宿主机 8088（kind NodePort 映射到前端）、3300（Grafana）；`ftsm-p6b-acc` 项目：MySQL 73306、Redis 76379、Kafka 79092、后端 78080、LGTM 73000 / 74318 | 停止 |
| 凭据 | 不需要（镜像在本地构建，用 `kind load` 导入）；使用 GHCR 镜像时需要人工创建 pull secret（可选） | — |

## 3. 阶段输入与输出

**输入**：

- 4 个 `@Scheduled` 任务：`SeckillService.reconcileEvents`、`OutboxRelay.run`、`SeckillReconciler.run`、`OutboxJanitor.run`；P5b 已合并时还有 `CatalogIndexConfig` 中的对账任务；
- `OutboxDao`、`OutboxPublisher`、`SeckillService.buy`；
- 3 个 Dockerfile；
- CI 已经能发布镜像。

**输出与契约**：

| 输出 | 契约 |
| --- | --- |
| `V4__shedlock.sql`、`V5__outbox_traceparent.sql` | 下一个迁移编号是 V6；P7 没有迁移 |
| 锁名（§6.1） | 新增定时任务时，必须在这张表里登记锁名，或者说明为什么不需要锁 |
| 探针路径与 readiness 组成（§6.2） | K8s 清单、compose 的 healthcheck |
| 指标名称表（§6.4） | Dashboard 和告警 |
| `k8s/`、`observability/`、`scripts/p6b/*` | 部署与验收 |

## 4. 逐文件清单

缩写：
- `J=backend/src/main/java/my/edu/ukm/ftsm/ecommerce`
- `R=backend/src/main/resources`
- `T=backend/src/test/java/my/edu/ukm/ftsm/ecommerce`

### 4.1 应用

| 操作 | 路径 | 职责 | 验证 |
| --- | --- | --- | --- |
| 修改 | `pom.xml`（根） | 属性 `shedlock.version=6.10.0`；在 `dependencyManagement` 中声明 `net.javacrumbs.shedlock:shedlock-spring` 和 `shedlock-provider-jdbc-template` | A1 |
| 修改 | `backend/pom.xml` | 新增 `shedlock-spring`、`shedlock-provider-jdbc-template`、`io.opentelemetry:opentelemetry-api`（Boot 管理版本为 1.49.0；`W3CTraceContextPropagator` 就在这个 artifact 里 **[源码：jar]**；`Context` 和 `Scope` 来自它的传递依赖 `opentelemetry-context`）、`io.micrometer:micrometer-registry-otlp`（Boot 管理） | A1 |
| 新增 | `R/db/migration/V4__shedlock.sql` | §6.1 | A2 |
| 新增 | `R/db/migration/V5__outbox_traceparent.sql` | `ALTER TABLE order_outbox ADD COLUMN traceparent VARCHAR(64) NULL;` | A2 |
| 新增 | `J/config/SchedulingLockConfig.java` | `@EnableSchedulerLock(defaultLockAtMostFor="PT1M")`；`LockProvider` 使用 `JdbcTemplateLockProvider`，配置 `usingDbTime()` 和表名 `shedlock` | A3 |
| 修改 | `J/service/SeckillService.java` | `reconcileEvents` 加 `@SchedulerLock`；`buy` 在写 outbox 时写入 traceparent | A3、A6 |
| 修改 | `J/service/SeckillReconciler.java`、`J/service/OutboxJanitor.java`；P5b 已合并时还有 `J/config/CatalogIndexConfig.java` | 加 `@SchedulerLock` | A3 |
| 修改 | `J/repository/OutboxDao.java` | `insert` 增加参数 `String traceparent`；`lockBatch` 的结果中包含 traceparent | A6 |
| 修改 | `J/service/OutboxPublisher.java` | 每一条记录都在提取出的父 context 内发送（§6.3） | A6 |
| 新增 | `J/observability/TraceContextSupport.java` | `static String currentTraceparent()`；`static Context parentFrom(String traceparent)` | `TraceContextSupportTest` |
| 新增 | `J/observability/SeckillMetrics.java` | gauge `seckill.outbox.backlog`（每 5 秒刷新一次缓存值）；counter `seckill.buy`，tag 为 `result` | `SeckillMetricsTest` |
| 修改 | `J/controller/SeckillController.java` | 调用 `metrics.countBuy(result)` | 同上 |
| 修改 | `J/config/SecurityConfig.java` | 允许匿名 `GET /actuator/health/liveness` 和 `/actuator/health/readiness`（精确路径） | `ProbeSecurityTest` |
| 修改 | `R/application.yml` | §7.1 | A4 |
| 修改 | `mcp-server/src/main/resources/application.yml` | 探针配置，readiness 包含 db 和 redis | A4 |
| 修改 | `backend/Dockerfile`、`mcp-server/Dockerfile` | 用 `ADD --checksum` 下载 OTel agent 2.32.0（§6.3） | A5 |
| 新增 | `T/observability/TraceContextSupportTest.java`、`T/observability/SeckillMetricsTest.java`、`T/config/ProbeSecurityTest.java` | §8 | A1 |
| 新增 | `T/it/MultiInstanceIT.java`、`T/it/TraceparentIT.java`、`T/it/ProbesIT.java` | §8 | A1 |
| 修改 | `scripts/ci/expected-tests.json` | 加入上面 3 个 IT | A1 |

### 4.2 可观测性与 Compose

| 操作 | 路径 | 职责 | 验证 |
| --- | --- | --- | --- |
| 修改 | `docker-compose.yml` | 新增 `lgtm` 服务（`grafana/otel-lgtm:0.35.0`，端口 `${GRAFANA_HOST_PORT:-3000}:3000` 和 `${OTLP_HTTP_HOST_PORT:-4318}:4318`）；backend 和 mcp-server 的环境变量见 §7.2 | A6 |
| 新增 | `observability/grafana/dashboards/seckill.json` | 6 个面板（§6.4） | A7 |
| 新增 | `scripts/p6b/import_dashboard.sh` | 通过 Grafana HTTP API `POST /api/dashboards/db` 导入（不依赖镜像内部的 provisioning 路径） | A7 |
| 新增 | `scripts/p6b/check_metrics.py` | 通过 `GET /api/datasources` 找到 type 为 prometheus 的数据源 uid，再经 `/api/datasources/proxy/uid/<uid>/api/v1/label/__name__/values` 查询，断言 §6.4 中的每个指标名都存在 | A7 |
| 新增 | `scripts/p6b/check_trace.py` | 找到 type 为 tempo 的数据源，按 `service.name=ftsm-backend` 搜索最近的 trace，断言其中存在同时包含 HTTP POST、`order_outbox` 的 INSERT、Kafka publish、Kafka process、`orders` 的 INSERT 这五类 span 的 trace | A6 |
| 新增 | `scripts/p6b/acceptance_compose.sh` | 依次执行 A4–A8 | — |

### 4.3 Kubernetes

| 操作 | 路径 | 必需的字段与要点 |
| --- | --- | --- |
| 新增 | `k8s/kind/cluster.yaml` | `kind: Cluster`，`apiVersion: kind.x-k8s.io/v1alpha4`；1 个 control-plane 节点，`image: kindest/node:v1.37.0@sha256:a1ed56cfb0e7b93589bdf97c8cd566405a265939e3620fc4f5de89adff580ae5`；`extraPortMappings`：`{containerPort: 30080, hostPort: 8088}`、`{containerPort: 30300, hostPort: 3300}` |
| 新增 | `k8s/kustomization.yaml` | `namespace: ftsm`；`resources:` 列出下面的全部清单；`secretGenerator: [{name: ftsm-secrets, envs: [secrets.env]}]`；`configMapGenerator: [{name: ftsm-config, envs: [config.env]}, {name: mysql-init, files: [../mysql/init/01-readonly-user.sh]}]`；`images:`（name `ftsm-backend`、`ftsm-mcp-server`、`ftsm-frontend`，`newTag: dev`） |
| 新增 | `k8s/namespace.yaml` | `Namespace ftsm` |
| 新增 | `k8s/config.env` | 非敏感配置：`DB_NAME=ftsm_ecommerce`、`KAFKA_BOOTSTRAP=kafka-0.kafka.ftsm.svc.cluster.local:9092`、`REDIS_HOST=redis`、`MCP_SERVER_URL=http://mcp-server:8081`、`OTEL_EXPORTER_OTLP_ENDPOINT=http://lgtm:4318`、`OTLP_METRICS_URL=http://lgtm:4318/v1/metrics`、`OTLP_METRICS_ENABLED=true`、`LLM_PROVIDER=none`、`EMBEDDING_PROVIDER=none`、`SPRING_PROFILES_ACTIVE=prod` |
| 新增 | `k8s/secrets.env.example` | `DB_PASSWORD=`、`JWT_SECRET=`、`MCP_DB_PASSWORD=`、`LLM_API_KEY=`、`OPENAI_API_KEY=`；真实的 `k8s/secrets.env` 加入 `.gitignore` |
| 新增 | `k8s/mysql.yaml` | headless `Service mysql`（3306）；`StatefulSet mysql`：`serviceName: mysql`，`replicas: 1`，镜像 `mysql:8.0.46`，`env`（`MYSQL_ROOT_PASSWORD` 来自 secret 的 `DB_PASSWORD`，`MYSQL_DATABASE` 来自 configmap，`MCP_DB_PASSWORD` 来自 secret），`volumeMounts`（`/var/lib/mysql`，以及 `/docker-entrypoint-initdb.d` 挂载 configMap `mysql-init`），`readinessProbe exec: mysqladmin ping`，`volumeClaimTemplates`（2Gi，`storageClassName: standard`，`ReadWriteOnce`） |
| 新增 | `k8s/redis.yaml` | `Service redis`（6379）；`StatefulSet redis`：`redis:8.10.2`，`args: [--appendonly, "yes"]`，PVC 1Gi，`readinessProbe exec: redis-cli ping` |
| 新增 | `k8s/kafka.yaml` | headless `Service kafka`（9092、9093）；`StatefulSet kafka`：`replicas: 1`，镜像 `apache/kafka:3.9.2`；KRaft 的环境变量同 P2 §7.3，其中 `KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://kafka-0.kafka.ftsm.svc.cluster.local:9092`、`KAFKA_CONTROLLER_QUORUM_VOTERS=1@kafka-0.kafka.ftsm.svc.cluster.local:9093`、`KAFKA_LISTENERS=PLAINTEXT://:9092,CONTROLLER://:9093`；PVC 2Gi；`readinessProbe exec` 执行 `kafka-broker-api-versions.sh` |
| 新增 | `k8s/backend.yaml` | `Service backend`（8080）；`Deployment backend`：`replicas: 2`，`image: ftsm-backend:dev`，`imagePullPolicy: IfNotPresent`；`initContainers`：`wait-mysql`（`mysql:8.0.46`，执行 `until mysqladmin ping -h mysql --silent; do sleep 2; done`，由 `timeoutSeconds` 兜底）、`wait-kafka`（`apache/kafka:3.9.2`，执行 `kafka-broker-api-versions.sh --bootstrap-server $KAFKA_BOOTSTRAP` 循环）；`envFrom`（configMap + secret）；`env JAVA_TOOL_OPTIONS=-javaagent:/otel/opentelemetry-javaagent.jar`，以及 `OTEL_SERVICE_NAME=ftsm-backend` 等；`resources: {requests: {cpu: 500m, memory: 768Mi}, limits: {memory: 1Gi}}`；`livenessProbe httpGet /actuator/health/liveness`（`initialDelaySeconds: 60`，`periodSeconds: 10`，`failureThreshold: 6`）；`readinessProbe httpGet /actuator/health/readiness`（`initialDelaySeconds: 30`，`periodSeconds: 5`）；`volumeMounts /app/uploads` → PVC `uploads` |
| 新增 | `k8s/uploads-pvc.yaml` | `PersistentVolumeClaim uploads`：`ReadWriteOnce`，1Gi，`storageClassName: standard`（kind 是单节点集群，同一节点上的多个 Pod 可以共用一个 RWO 卷；限制见 §10） |
| 新增 | `k8s/backend-hpa.yaml` | `autoscaling/v2 HorizontalPodAutoscaler backend`：`minReplicas: 2`，`maxReplicas: 6`，CPU 利用率目标 70% |
| 新增 | `k8s/mcp-server.yaml` | `Service mcp-server`（8081）；`Deployment`：`replicas: 1`；`initContainer wait-backend`（`busybox:1.37.0`，执行 `until wget -qO- http://backend:8080/actuator/health/readiness; do sleep 3; done`，这样能保证迁移已经完成）；探针同 backend |
| 新增 | `k8s/frontend.yaml` | `Service frontend`：`type: NodePort`，`nodePort: 30080`，`port: 80`；`Deployment`：`replicas: 1`，`image: ftsm-frontend:dev`。nginx 中的 `backend:8080` 直接解析到 K8s 的 Service |
| 新增 | `k8s/lgtm.yaml` | `Service lgtm`：3000（NodePort 30300）、4317、4318；`Deployment`：`grafana/otel-lgtm:0.35.0` |
| 新增 | `k8s/overlays/local-path/kustomization.yaml` | `resources: [../../]`；对 3 个 StatefulSet 的 `volumeClaimTemplates` 和 `uploads` PVC 用 JSON6902 patch，把 `storageClassName` 改为 `local-path`。只有当默认 StorageClass 的 provisioner 不是 `rancher.io/local-path` 时才使用 |
| 新增 | `k8s/README.md` | §7.4 中的全部命令 |
| 新增 | `scripts/p6b/install_tools.sh` | 下载 kind、kubectl、kubeconform，并按 §2 的 sha256 校验，安装到 `.tools/` |
| 新增 | `scripts/p6b/validate_manifests.sh` | 执行 `kubectl kustomize k8s/ > $TMP/rendered.yaml`，然后 `kubeconform -strict -summary -kubernetes-version 1.37.0 $TMP/rendered.yaml`；如果下载 1.37.0 的 schema 失败，改用 kubeconform 能找到的最新版本，并在输出里注明 |
| 新增 | `scripts/p6b/kind_up.sh`、`scripts/p6b/kind_down.sh` | §7.4 |
| 修改 | `scripts/db/lib.sh`、`loadtest/drain.sh`、`loadtest/run.sh` | 新增 MySQL 目标 `k8s:<namespace>`（通过 `kubectl -n <ns> exec -i mysql-0 -- env MYSQL_PWD=… mysql -uroot …` 访问），以及 drain 模式 `outbox-k8s`（outbox 积压用 SQL 查询；consumer lag 通过 `kubectl -n <ns> exec kafka-0 -- /opt/kafka/bin/kafka-consumer-groups.sh …` 查询） | A11 |
| 新增 | `scripts/p6b/evidence/.gitkeep` | 证据目录 | — |
| 修改 | `.gitignore` | `k8s/secrets.env` |
| 修改 | `README.md`、`HANDOFF.md`、`CHANGELOG.md` | v0.13.0；部署与可观测性 |

**禁止修改**：已合并的迁移文件；业务语义；`OutboxRelay` 的调度方式（**不得**给它加锁）。

## 5. 实施顺序

| # | 任务 | 完成条件 | 持久状态 |
| --- | --- | --- | --- |
| 1 | 依赖、V4、ShedLock 配置与注解 | `MultiInstanceIT` 通过 | 文件 |
| 2 | 探针与安全配置 | `ProbesIT`、`ProbeSecurityTest` 通过 | 文件 |
| 3 | V5、traceparent 的写入与恢复 | `TraceparentIT`、`TraceContextSupportTest` 通过 | 文件 |
| 4 | 指标 | `SeckillMetricsTest` 通过 | 文件 |
| 5 | Dockerfile、compose 中的 lgtm；compose 验收 | A4–A8 通过 | `ftsm-p6b-acc` 项目 |
| 6 | K8s 清单与静态校验 | A9 通过 | 文件 |
| 7 | kind 实测 | A10–A14 | kind 集群 `ftsm` |
| 8 | 清理（`kind delete cluster --name ftsm`，`down -v`）；文档；PR | §11 | — |

## 6. 实现契约

### 6.1 ShedLock

```sql
-- V4__shedlock.sql
CREATE TABLE shedlock (
  name VARCHAR(64) NOT NULL PRIMARY KEY,
  lock_until TIMESTAMP(3) NOT NULL,
  locked_at TIMESTAMP(3) NOT NULL,
  locked_by VARCHAR(255) NOT NULL
);
```

| 任务 | 调度周期 | 锁名 | `lockAtMostFor` | `lockAtLeastFor` | 正常耗时上界 |
| --- | --- | --- | --- | --- | --- |
| `SeckillService.reconcileEvents` | fixedDelay 10 s | `seckill-reconcile-events` | PT30S | PT5S | < 2 s |
| `SeckillReconciler.run` | fixedDelay 60 s | `seckill-reconciler` | PT5M | PT10S | < 30 s |
| `OutboxJanitor.run` | 每小时一次 | `outbox-janitor` | PT30M | PT1M | < 5 min |
| `CatalogIndexConfig.reconcile`（P5b） | 10 min | `catalog-reconcile` | PT15M | PT1M | < 10 min |
| `OutboxRelay.run` | fixedDelay 100 ms | **不加锁** | — | — | — |

- **锁过期**：任务运行超过 `lockAtMostFor` 之后，锁会被视为过期，另一个副本可能同时开始执行同一个任务。因此上表的每个任务都必须是幂等的：预热用 `SET NX`；对账的标记是幂等的；Janitor 的删除是幂等的；索引对账基于哈希比较。
- `lockAtLeastFor` 的作用是防止各副本之间的时钟偏差导致任务被连续执行。
- 时间以数据库时间为准（`usingDbTime()`）。
- **为什么不锁 `OutboxRelay`**：`FOR UPDATE SKIP LOCKED` 让多个副本可以各自取走不同的行、并行发送；加锁反而会让吞吐退化为单副本。`MultiInstanceIT` 负责证明这样做不会产生重复订单。

### 6.2 探针

```yaml
management:
  endpoint:
    health:
      probes: { enabled: true }
      group:
        liveness:  { include: livenessState }
        readiness: { include: 'readinessState,db,redis' }
```

- readiness **不包含** Kafka（Kafka 不可用时，outbox 可以积压，下单仍然能接受）和 MCP（MCP 不可用时有降级）。
- mcp-server 的 readiness 包含 `readinessState`、`db`、`redis`。
- `SecurityConfig` 允许匿名访问（只放行 GET 方法，且是精确路径）：`/actuator/health`、`/actuator/health/liveness`、`/actuator/health/readiness`。其他 actuator 端点保持现状。

### 6.3 Trace 上下文

**写入**（`SeckillService.buy`，写 outbox 之前）：

```java
String traceparent = TraceContextSupport.currentTraceparent();
// W3CTraceContextPropagator.getInstance().inject(Context.current(), map, Map::put); return map.get("traceparent")
```

没有挂 agent 时，`Context.current()` 中没有有效的 span，`inject` 什么都不写，结果为 null，outbox 列就是 null。

**恢复**（`OutboxPublisher.publishBatch`，处理每一行时）：

```java
Context parent = TraceContextSupport.parentFrom(row.traceparent());   // null 或格式不合法时返回 Context.root()
try (Scope ignored = parent.makeCurrent()) {
    futures.add(kafka.send(topic, key, row.payload()));
}
```

- 有 agent 时，agent 的 Kafka 生产者埋点会在当前 context 下创建 span，并把 traceparent 写进消息头；消费端会自动延续。
- `Scope` 必须用 try-with-resources 关闭。
- 没有 agent 时，以上调用全部是 no-op。

**Dockerfile**（第一行必须是 `# syntax=docker/dockerfile:1.7`）：

```dockerfile
ADD --checksum=sha256:f787eb6c7f3d18e69a431e108a15278d25ee37f83d68b678f621e063f3988f82 \
    https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.32.0/opentelemetry-javaagent.jar \
    /otel/opentelemetry-javaagent.jar
```

checksum 是 2026-10-03 对实际下载的文件计算得到的，jar 中的 `Implementation-Version: 2.32.0` **[实验]**。agent 只有在设置了 `JAVA_TOOL_OPTIONS` 时才会生效。

### 6.4 指标：来源、配置、查询与验证

**指标的走向**：

- Micrometer → `micrometer-registry-otlp` → 推送到 LGTM 的 `:4318/v1/metrics`。
- 对 agent 设置 `OTEL_METRICS_EXPORTER=none`、`OTEL_LOGS_EXPORTER=none`，并设置 `OTEL_INSTRUMENTATION_MICROMETER_ENABLED=false`（agent 内确实有 micrometer 的埋点模块 **[源码]**，所以这里显式关闭，不依赖它的默认值），这样不会出现重复的指标。
- agent 只负责 traces。

| 面板 | Micrometer 指标（来源） | 必要配置 | PromQL（**指标名为预期值，以 `check_metrics.py` 实际查到的为准**） |
| --- | --- | --- | --- |
| 下单 QPS | `http.server.requests`（Spring MVC 自带的 observation） | 无 | `sum(rate(http_server_requests_milliseconds_count{uri="/api/seckill/{eventId}/buy"}[1m]))` |
| 下单 p99 | 同上 | `management.metrics.distribution.percentiles-histogram.http.server.requests: true` | `histogram_quantile(0.99, sum by (le) (rate(http_server_requests_milliseconds_bucket{uri="/api/seckill/{eventId}/buy"}[1m])))` |
| outbox 积压 | `seckill.outbox.backlog`（自定义 gauge） | `SeckillMetrics` | `max(seckill_outbox_backlog)` |
| 消费延迟 | `kafka.consumer.fetch.manager.records.lag.max`（Boot 为 Kafka 消费者工厂自动挂上的 Micrometer listener） | 无 | `max(kafka_consumer_fetch_manager_records_lag_max{client_id=~".*seckill.*"})` |
| Hikari 活跃连接 | `hikaricp.connections.active` | 无 | `sum(hikaricp_connections_active)` |
| 熔断器状态 | `resilience4j.circuitbreaker.state`（resilience4j-spring-boot3 的 Micrometer 绑定） | 无 | `max by (state) (resilience4j_circuitbreaker_state{name="llm"})` |

指标从 OTLP 转为 Prometheus 时的命名（是否加单位后缀、点号转下划线）**[未验证]**。`check_metrics.py` 负责实测：如果实际名称不同，在同一个 PR 中修改 dashboard JSON 和本表，并在 PR 描述中列出差异。

### 6.5 Dashboard 的导入

```bash
scripts/p6b/import_dashboard.sh http://127.0.0.1:${GRAFANA_HOST_PORT}
```

默认使用 `admin:admin`，可以通过环境变量 `GRAFANA_USER` 和 `GRAFANA_PASSWORD` 覆盖。导入时 `overwrite=true`。返回 401 时退出码 2，并提示设置凭据。

## 7. 配置与运行

### 7.1 backend 的 `application.yml` 新增

```yaml
management:
  endpoint: { health: { probes: { enabled: true }, group: { liveness: { include: livenessState }, readiness: { include: 'readinessState,db,redis' } } } }
  metrics: { distribution: { percentiles-histogram: { http.server.requests: true } } }
  otlp:
    metrics:
      export:
        enabled: ${OTLP_METRICS_ENABLED:false}
        url: ${OTLP_METRICS_URL:http://localhost:4318/v1/metrics}
        step: 15s
```

### 7.2 环境变量

| 变量 | 默认 | 消费者 | compose 内 | K8s |
| --- | --- | --- | --- | --- |
| `OTLP_METRICS_ENABLED` | `false` | backend、mcp-server | `true` | configmap |
| `OTLP_METRICS_URL` | `http://localhost:4318/v1/metrics` | 同上 | `http://lgtm:4318/v1/metrics` | `http://lgtm:4318/v1/metrics` |
| `JAVA_TOOL_OPTIONS` | 不设置 | JVM | `-javaagent:/otel/opentelemetry-javaagent.jar` | 同左 |
| `OTEL_SERVICE_NAME` | — | agent | `ftsm-backend` / `ftsm-mcp-server` | 同左 |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | — | agent | `http://lgtm:4318` | 同左 |
| `OTEL_EXPORTER_OTLP_PROTOCOL` | — | agent | `http/protobuf` | 同左 |
| `OTEL_METRICS_EXPORTER`、`OTEL_LOGS_EXPORTER` | — | agent | `none` | 同左 |
| `OTEL_INSTRUMENTATION_MICROMETER_ENABLED` | — | agent | `false` | 同左 |
| `GRAFANA_HOST_PORT`、`OTLP_HTTP_HOST_PORT` | `3000`、`4318` | compose | — | — |

compose 的 backend 和 mcp-server 都要在 `environment` 中显式写出上表中对应的条目（compose 不会把 env 文件里的变量自动传进容器）。

### 7.3 Compose 验收

项目名 `ftsm-p6b-acc`，使用显式的 `--env-file`，端口按 §2；等待方式统一用 `wait_http`。

### 7.4 kind 的完整流程（`scripts/p6b/kind_up.sh`，工作目录 `$REPO`）

```bash
set -Eeuo pipefail
source scripts/lib/wait.sh
eval "$(scripts/p6b/install_tools.sh)"                  # 输出 KIND=… KUBECTL=… KUBECONFORM=… 三行，并已通过 sha256 校验
"$KIND" create cluster --name ftsm --config k8s/kind/cluster.yaml --wait 180s
# metrics-server（HPA 需要）
"$KUBECTL" apply -f https://github.com/kubernetes-sigs/metrics-server/releases/download/v0.9.0/components.yaml
"$KUBECTL" -n kube-system patch deployment metrics-server --type=json \
  -p='[{"op":"add","path":"/spec/template/spec/containers/0/args/-","value":"--kubelet-insecure-tls"}]'
"$KUBECTL" -n kube-system rollout status deployment/metrics-server --timeout=180s
# 存储：kind 会安装默认的 StorageClass "standard" [源码：kind installstorage/storage.go]，较新的 node 镜像自带存储驱动
prov=$("$KUBECTL" get storageclass standard -o jsonpath='{.provisioner}')
if [[ $prov != rancher.io/local-path ]]; then
  "$KUBECTL" apply -f https://raw.githubusercontent.com/rancher/local-path-provisioner/v0.0.37/deploy/local-path-storage.yaml
  "$KUBECTL" -n local-path-storage rollout status deployment/local-path-provisioner --timeout=180s
  OVERLAY=k8s/overlays/local-path        # 把所有 PVC 的 storageClassName 改为 local-path
else
  OVERLAY=k8s
fi
# 镜像
docker build -f backend/Dockerfile -t ftsm-backend:dev .
docker build -f mcp-server/Dockerfile -t ftsm-mcp-server:dev .
docker build -t ftsm-frontend:dev frontend
for i in ftsm-backend:dev ftsm-mcp-server:dev ftsm-frontend:dev; do "$KIND" load docker-image "$i" --name ftsm; done
# 密钥：写在仓库外的临时文件里，由 kustomize 读取
cp k8s/secrets.env.example k8s/secrets.env       # 再填入随机值：DB_PASSWORD 和 JWT_SECRET 用 openssl rand -hex 24
"$KUBECTL" apply -k "$OVERLAY"
for s in mysql redis kafka; do "$KUBECTL" -n ftsm rollout status statefulset/$s --timeout=300s; done
for d in backend mcp-server frontend lgtm; do "$KUBECTL" -n ftsm rollout status deployment/$d --timeout=600s; done
wait_http http://127.0.0.1:8088/api/products 120
```

**使用 GHCR 镜像（可选）**：

- 由人执行 `kubectl -n ftsm create secret docker-registry ghcr-pull --docker-server=ghcr.io --docker-username=… --docker-password=<PAT>`；
- 在 kustomization 中把 `images.newName` 和 `newTag` 改为 GHCR 的地址和 sha；
- 在 Deployment 中加上 `imagePullSecrets: [{name: ghcr-pull}]`。

**清理**（`kind_down.sh`）：`"$KIND" delete cluster --name ftsm`，然后删除 `k8s/secrets.env`。只删除名为 `ftsm` 的集群。

## 8. 测试清单

| 类 | 场景 |
| --- | --- |
| `MultiInstanceIT` | 在 `AbstractIntegrationTest` 的上下文之外，再用 `SpringApplicationBuilder` 启动第二个上下文（`server.port=0`，连接到同一组 Testcontainers，连接信息从第一个上下文的 `Environment` 中复制）。并发下单 100 笔，并让两个实例都调用 `reconcileEvents()` 10 次。断言：没有重复订单；订单数 = 库存；`shedlock` 表中有 `seckill-reconcile-events` 这一行；预热日志在两个实例中合计只出现 1 次。`finally` 中关闭第二个上下文 |
| `TraceparentIT` | 不挂 agent 时，outbox 的 `traceparent` 为 null，发送正常；手工 `makeCurrent` 一个合法的 span context 后调用 `buy`，outbox 中保存了相同的 trace id；`parentFrom` 遇到非法字符串时返回 root context，不抛异常 |
| `ProbesIT` | 匿名访问 `/actuator/health/liveness` 和 `/readiness` 都返回 200 且状态为 UP；`readiness` 的 `components` 中包含 db 和 redis，**不包含** kafka |
| `ProbeSecurityTest` | `@WebMvcTest` 加安全配置：两个探针路径允许匿名访问，`/actuator/info` 的访问规则保持不变 |
| `TraceContextSupportTest` | 对 inject 和 extract 做往返测试 |
| `SeckillMetricsTest` | 用 `SimpleMeterRegistry`：counter 按 result 计数；gauge 只读取缓存值，不会每次读取都查一次数据库（mock `OutboxDao`，验证调用次数） |

## 9. 验收矩阵

| 编号 | 验收目标 | 前置/fixture | 工作目录 | 完整命令 | 预期断言 | 证据 | 自动/人工 | 阻塞 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| A1 | 构建、测试、报告检查 | Docker | `$REPO` | `./mvnw -B verify && python3 scripts/ci/check_test_reports.py --expect scripts/ci/expected-tests.json` | 全部通过 | reports | 自动 | 是 |
| A2 | 迁移 | — | `$REPO` | `MigrationIT` 的期望版本改为 `[1,2,3,4,5]`（以及可能存在的 1.1） | 通过 | reports | 自动 | 是 |
| A3 | 锁表 | — | `$REPO` | `grep -c '@SchedulerLock' $(grep -rl '@Scheduled' backend/src/main/java)`；同时确认 `OutboxRelay.java` 中**没有** `@SchedulerLock` | 每个文件的计数与 §6.1 的表一致 | PR | 自动 | 是 |
| A4 | compose 中的探针 | compose | `$REPO` | `curl -fsS 127.0.0.1:78080/actuator/health/readiness` | 返回 200，`components` 中有 db 和 redis，没有 kafka | `scripts/p6b/evidence/a4.json` | 自动 | 是 |
| A5 | agent 已就位 | — | `$REPO` | `docker run --rm --entrypoint sh ftsm-backend:p6b -c 'unzip -p /otel/opentelemetry-javaagent.jar META-INF/MANIFEST.MF \| grep Implementation-Version'` | 输出中有 `2.32.0` | PR | 自动 | 是 |
| A6 | 跨 Kafka 的 trace | compose，lgtm 已启动 | `$REPO` | 下单 1 笔，等 30 s，然后执行 `python3 scripts/p6b/check_trace.py --grafana http://127.0.0.1:73000` | 退出码 0（找到包含 5 类 span 的 trace） | `…/a6.json` | 自动 | 是 |
| A7 | 指标与 dashboard | A6 | `$REPO` | `scripts/p6b/import_dashboard.sh …`，然后 `python3 scripts/p6b/check_metrics.py --grafana …` | 两者都成功 | `…/a7.txt` | 自动 | 是 |
| A8 | compose 下的 e2e 不回归 | A4 | `$REPO` | 按 P4a §7 的日志跟随方式执行 e2e | 8/8 | `…/a8.txt` | 自动 | 是 |
| A9 | 清单的静态校验 | — | `$REPO` | `scripts/p6b/validate_manifests.sh` | 退出码 0，没有 invalid 资源 | `…/a9.txt` | 自动 | 是 |
| A10 | kind 部署 | kind 可用 | `$REPO` | `scripts/p6b/kind_up.sh` | 退出码 0；`kubectl -n ftsm get pods` 中所有 Pod 都是 Running 且 Ready | `…/a10.txt` | 自动 | 是；kind 不可用时 PR 保持 draft |
| A11 | 集群内下单 | A10 | `$REPO` | `loadtest/run.sh --config K8S --scenario contention --base http://127.0.0.1:8088 --stock 5 --buyers 20 --vus 20 --reject-status 409 --drain outbox-k8s --mysql k8s:ftsm:ftsm_ecommerce --redis k8s:ftsm --out-root loadtest/results/_smoke` | 退出码 0（run.sh 内部已经核对订单数 = 5，以及积压和 lag 均为 0） | `…/a11/` | 自动 | 是 |
| A12 | 两个副本下预热只发生一次 | A10 | `$REPO` | 创建一个活动；`kubectl -n ftsm logs -l app=backend --tail=-1 \| grep -c 'warmed stock for event <id>'` | 输出 `1` | `…/a12.txt` | 自动 | 是 |
| A13 | 上传文件在副本之间共享 | A10 | `$REPO` | 通过 8088 上传一张图片，然后对两个 backend Pod 分别 `kubectl exec … ls /app/uploads/<file>` | 两个 Pod 中都能看到这个文件 | `…/a13.txt` | 自动 | 是 |
| A14 | HPA | A10 | `$REPO` | 对 8088 跑 throughput（`--rate` 从 200 起，持续 5 分钟），同时执行 `kubectl -n ftsm get hpa backend -w` | **证据要求**：TARGETS 一列显示为百分比（说明 HPA 拿到了指标，而不是 `<unknown>`）；CPU 利用率超过 70% 时，5 分钟内 `desiredReplicas > 2`。如果资源不足、CPU 达不到 70%，记录最高利用率和节点容量，这种情况**不判为失败**。不要求扩到 6 个副本 | 截图或输出（人工） | 人工 | 否 |
| A15 | 清理 | — | `$REPO` | `scripts/p6b/kind_down.sh`；`"${DC[@]}" down -v` | 名为 `ftsm` 的集群已不存在 | — | 自动 | 否 |

## 10. 升级、重跑与恢复

- V4 和 V5 只新增表和列，可以在线执行。不支持降级：如需回退，手工删除 `shedlock` 表和 `traceparent` 列，再删除 `flyway_schema_history` 中版本 4 和 5 两行。
- 多副本部署：B3 已被修复（`SET NX` 加 ShedLock）。上线顺序：先以单副本部署 P6b 的代码，确认 `shedlock` 表已创建，再扩到 2 个副本。
- **上传文件的限制**：RWO 卷只能在单节点集群中被多个 Pod 共享。多节点环境需要 RWX 存储（NFS 等）或对象存储，**本阶段不实现**。要迁移到这类存储，需要把 `/app/uploads` 中的文件复制到新卷，并保持 URL 路径不变。
- 重跑：`kind_up.sh` 遇到已存在的集群时会失败；先执行 `kind_down.sh` 再重新运行。compose 验收使用独立的项目名。
- 清理范围：只针对名为 `ftsm` 的 kind 集群、`ftsm-p6b-acc` 项目，以及 `k8s/secrets.env`。

## 11. 完成判定与 PR

- **合并**：A1–A13 全部通过。A14 只需提交证据，不阻塞合并。
- **draft**：
  - kind 无法运行（A10–A13 未执行，即使 A9 已经通过）；
  - `check_metrics` 或 `check_trace` 失败，并且无法通过修正指标名称来解决。
- **外部待办**：A14 的截图；可选的 GHCR 部署。
- **标题**：`P6b: ShedLock, probes, OpenTelemetry, Grafana, Kubernetes manifests (v0.13.0)`
- **描述**：沿用 P0 的模板，另加：
  - “Metric names actually observed vs expected”；
  - “Cluster capacity during HPA test”。

## 12. 独立 agent prompt

见 [`../agent-prompts/P6b.md`](../agent-prompts/P6b.md)。
