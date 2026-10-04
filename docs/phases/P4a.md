# P4a · Maven 多模块拆分（不改行为）

> 版本：v0.8.0 · 前置：P2、P3（本阶段要修改 P3 新增的 `loadtest/bench/run_config.sh`）· 下一阶段：P6a，然后 P4b
> 全局约定见 [`../CHANGE_SPEC.md`](../CHANGE_SPEC.md) §0。证据标记含义同 P0：**[实验]** / **[源码]** / **[未验证]**。

## 1. 目标与非目标

**问题**：P4b 的 mcp-server 需要和 backend 共用目录相关的实体、仓库和检索逻辑，但现在的仓库是单模块。

| | 实施前 | 实施后 |
| --- | --- | --- |
| 根目录 | 没有 pom | `pom.xml`（`ftsm-parent`，聚合两个模块，继承 `spring-boot-starter-parent:3.5.16`） |
| 模块 | `backend/` | `catalog-core/`（普通 jar）+ `backend/`（可执行 jar） |
| Wrapper | `backend/mvnw` | 仓库根目录的 `mvnw` |
| Docker 构建上下文 | `./backend` | 仓库根目录 |

**行为必须完全不变**：API、数据库、Redis key、测试数量、jar 名称（`backend/target/ecommerce-0.0.1-SNAPSHOT.jar`）都保持原样。

**非目标**：新增 mcp-server（P4b）；修改任何类的内容；重命名包；修改迁移文件。

## 2. 前置条件

| 类别 | 要求 | 缺失时 |
| --- | --- | --- |
| 已合并阶段 | P2、P3 的 agent PR；没有其他正在修改实体文件的 PR | 停止 |
| 工具 | JDK 21；Docker + Compose（用于 A6、A7） | 没有 Docker 时：A1–A5 照常执行，A6、A7 标“未执行”，PR 保持 draft |
| 端口 | `ftsm-p4a-acc` 项目：MySQL 43306、Redis 46379、Kafka 49092、后端 48080 | 停止 |
| 人工 / 凭据 | 不需要 | — |

## 3. 阶段输入与输出

**输入**：单模块的 `backend/`，`backend/mvnw`。

**输出与契约**：

| 输出 | 契约 |
| --- | --- |
| 根 `pom.xml` | 所有后续模块（P4b 的 mcp-server）都在 `<modules>` 中登记；第三方 BOM（Spring AI、Resilience4j、ShedLock）统一放在根 pom 的 `dependencyManagement` 里 |
| `catalog-core` 的包 | `my.edu.ukm.ftsm.ecommerce.model`（Product、Item、SeckillEvent、Review）、`…repository`（这四个实体的仓库）、`…utils.RedisKeys`。P4b、P5a 新增的检索代码放在 `…search` 包 |
| 依赖方向 | `backend → catalog-core`。catalog-core **不得**依赖 backend 或任何 Spring Web 类 |
| 构建命令 | 在仓库根目录执行 `./mvnw -B verify`；只打包后端用 `./mvnw -B -pl backend -am -DskipTests package` |

## 4. 逐文件清单

### 4.1 移动（必须用 `git mv`，内容不得修改）

前缀约定：
- `B=backend/src/main/java/my/edu/ukm/ftsm/ecommerce`
- `C=catalog-core/src/main/java/my/edu/ukm/ftsm/ecommerce`

| 旧路径 | 新路径 |
| --- | --- |
| `B/model/Product.java` | `C/model/Product.java` |
| `B/model/Item.java` | `C/model/Item.java` |
| `B/model/SeckillEvent.java` | `C/model/SeckillEvent.java` |
| `B/model/Review.java` | `C/model/Review.java` |
| `B/repository/ProductRepository.java` | `C/repository/ProductRepository.java` |
| `B/repository/ItemRepository.java` | `C/repository/ItemRepository.java` |
| `B/repository/SeckillEventRepository.java` | `C/repository/SeckillEventRepository.java` |
| `B/repository/ReviewRepository.java` | `C/repository/ReviewRepository.java` |
| `B/utils/RedisKeys.java` | `C/utils/RedisKeys.java` |
| `backend/mvnw` | `mvnw` |
| `backend/mvnw.cmd` | `mvnw.cmd` |
| `backend/.mvn/wrapper/maven-wrapper.properties` | `.mvn/wrapper/maven-wrapper.properties` |

留在 backend 的：
- `Order`、`User`、`Role`，以及它们的仓库；
- `OtpUtils`；
- `OutboxDao`；
- 全部 service、controller、config；
- `backend/src/main/resources/**`，包括全部迁移、Lua 脚本和 `application.yml`；
- 全部测试。

实施前要先检查：这 9 个类的 import 里没有 backend 专有的类。

```bash
grep -h '^import my.edu' <这9个文件> | grep -vE '\.model\.(Product|Item|SeckillEvent|Review)|\.repository\.|\.utils\.RedisKeys'
```

输出必须为空。

### 4.2 新增 / 修改

| 操作 | 完整路径 | 内容 | 验证 |
| --- | --- | --- | --- |
| 新增 | `pom.xml` | §6.1 | A1 |
| 新增 | `catalog-core/pom.xml` | §6.2 | A1、A3 |
| 修改 | `backend/pom.xml` | §6.3 | A1 |
| 修改 | `backend/Dockerfile` | §6.4 | A6 |
| 新增 | `.dockerignore` | §6.4 | A6 |
| 修改 | `docker-compose.yml` | `backend.build: {context: ., dockerfile: backend/Dockerfile}` | A6 |
| 修改 | `scripts/db/lib.sh`、`scripts/db/export_baseline_schema.sh`、`scripts/p1/*.sh`、`loadtest/bench/run_config.sh`、`loadtest/README.md` | 把 `cd backend && ./mvnw` 改成仓库根目录的 `./mvnw -pl backend -am`。jar 路径不变 | A5 |
| 修改 | `README.md`、`HANDOFF.md`、`CHANGELOG.md` | 构建命令、项目结构、v0.8.0 | A5 |

**禁止修改**：被移动的 9 个类的内容；`backend/src/main/resources/**`；`backend/src/test/**`；`frontend/**`。

## 5. 实施顺序

| # | 任务 | 完成条件 | 持久状态 |
| --- | --- | --- | --- |
| 1 | 记录拆分前的基线：在 `backend` 下执行 `./mvnw -B verify`，把 surefire 汇总复制到 `$P4A_TMP/before.txt`（用 §9 中 A2 的 python 统计脚本计算） | 文件存在 | 临时文件 |
| 2 | §4.1 的 import 检查 | 输出为空 | 否 |
| 3 | 移动 Wrapper | 仓库根目录的 `./mvnw -v` 显示 3.9.11 | 文件 |
| 4 | 编写根 pom 和 catalog-core 的 pom，修改 backend 的 pom | `./mvnw -B -q validate` 通过 | 文件 |
| 5 | `git mv` 9 个类 | `./mvnw -B -q -DskipTests package` 成功 | 文件 |
| 6 | 测试数量对比 | A2 通过 | 否 |
| 7 | 依赖方向检查 | A3 通过 | 否 |
| 8 | 修改 Docker 和 compose；容器验收 | A6、A7 通过 | `ftsm-p4a-acc` 项目 |
| 9 | 修改脚本和文档中的命令 | A5 通过 | 文件 |
| 10 | 清理，提 PR | §11 | — |

## 6. 实现契约

### 6.1 根 `pom.xml`

```xml
<project>
  <modelVersion>4.0.0</modelVersion>
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
</project>
```

### 6.2 `catalog-core/pom.xml`（普通 jar）

- `<parent>` 指向 `ftsm-parent`（`<relativePath>../pom.xml</relativePath>`）；`artifactId` 为 `catalog-core`；`packaging` 为 `jar`。
- 依赖：`spring-boot-starter-data-jpa`、`spring-boot-starter-data-redis`、`spring-boot-starter-validation`、`org.projectlombok:lombok`（`optional`）。
- `maven-compiler-plugin` 的 `annotationProcessorPaths` 中加入 lombok（写法与 backend 相同）。
- **不得声明** `spring-boot-maven-plugin`：starter-parent 只在 `pluginManagement` 里配置了这个插件，模块没有显式声明就不会执行 repackage，产出的是可以被依赖的普通 jar。
- 没有 `src/main/resources`，也没有测试。

### 6.3 `backend/pom.xml`

- `<parent>` 改为 `ftsm-parent`（`relativePath ../pom.xml`）；保留 `artifactId=ecommerce` 和版本号，因此 jar 名不变。
- 删除自身的 `<properties><java.version>`（改为继承）。
- 新增依赖 `my.edu.ukm.ftsm:catalog-core`（版本由父 pom 的 dependencyManagement 管理）。
- 保留 `spring-boot-maven-plugin`，产出可执行 jar。
- 扫描：`FtsmEcommerceApplication` 位于根包 `my.edu.ukm.ftsm.ecommerce`，`@SpringBootApplication` 默认的组件扫描、`@EntityScan` 和 JPA 仓库扫描都会覆盖 classpath 上同名包里的类（包括 catalog-core 的 jar），因此**不需要**任何新增注解。A4 用来证明这一点。

### 6.4 Docker

`backend/Dockerfile`：

```dockerfile
# syntax=docker/dockerfile:1.7
FROM maven:3.9.11-eclipse-temurin-21-noble AS build
WORKDIR /src
COPY pom.xml ./
COPY catalog-core/pom.xml catalog-core/
COPY backend/pom.xml backend/
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -pl backend -am dependency:go-offline || true
COPY catalog-core/src catalog-core/src
COPY backend/src backend/src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -pl backend -am -DskipTests package

FROM eclipse-temurin:21.0.12.1_1-jre-noble
WORKDIR /app
COPY --from=build /src/backend/target/ecommerce-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
```

- `go-offline` 在多模块下可能因为 catalog-core 还没有安装而部分失败。它只用于预热缓存，所以加了 `|| true`；真正的失败由下一行的 `package` 暴露。
- `.dockerignore` 至少包含：

  ```text
  **/target
  frontend/node_modules
  frontend/dist
  .git
  .tools
  loadtest/results
  scripts/**/evidence
  ```

## 7. 配置与运行

- 不新增任何环境变量。
- 容器验收使用 `ftsm-p4a-acc` 项目，env 文件写法同 P2 §7.4，端口按 §2，不读取 `.env`。

e2e 的日志采集方式（日志持续写入文件，并保留 e2e 的原始退出码）：

```bash
DC=(docker compose -p ftsm-p4a-acc --env-file "$P4A_TMP/compose.env")
"${DC[@]}" up -d --build mysql redis kafka backend
source scripts/lib/wait.sh
wait_http http://127.0.0.1:48080/actuator/health 300 || { "${DC[@]}" logs --tail 200 backend; exit 1; }
"${DC[@]}" logs --no-color -f backend > "$P4A_TMP/backend.log" 2>&1 &
LOGPID=$!
set +e
python3 scripts/e2e_test.py --base http://127.0.0.1:48080 --log "$P4A_TMP/backend.log" --users 30 --stock 10
rc=$?
set -e
kill "$LOGPID"; wait "$LOGPID" 2>/dev/null
exit $rc
```

## 8. 测试清单

| 行为 | 测试 |
| --- | --- |
| 编译与全部单元测试 | 在仓库根目录执行 `./mvnw -B verify` |
| 测试数量与拆分前一致 | A2 |
| 依赖方向 | A3 |
| 实体和仓库仍能被扫描到 | A4（启动后 Hibernate validate 通过，证明实体都已注册；API 冒烟） |
| 容器构建与端到端 | A6、A7 |
| AI | 不适用 |

## 9. 验收矩阵

| 编号 | 验收目标 | 前置/fixture | 工作目录 | 完整命令 | 预期断言 | 证据 | 自动/人工 | 阻塞 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| A1 | 多模块构建 | — | `$REPO` | `./mvnw -B verify` | BUILD SUCCESS；reactor 顺序为 ftsm-parent → catalog-core → ecommerce | PR | 自动 | 是 |
| A2 | 测试数量不变 | 步骤 1 | `$REPO` | `python3 - <<'PY'`（统计 `backend/target/surefire-reports/TEST-*.xml` 中各项的总和）`PY` 的输出与 `$P4A_TMP/before.txt` 做 `diff` | tests、failures、errors、skipped 四项全部一致 | PR | 自动 | 是 |
| A3 | 不存在循环依赖或反向依赖 | — | `$REPO` | `./mvnw -B -q -pl catalog-core dependency:list -DoutputFile=/dev/stdout \| grep -cE 'my\.edu\.ukm\.ftsm:ecommerce\|spring-webmvc\|spring-web:'` | 输出 0 | PR | 自动 | 是 |
| A4 | 扫描正常 | P0 的临时 MySQL / Redis 与 P2 的 Kafka，或者 compose | `$REPO` | 用 `start_backend backend/target/ecommerce-0.0.1-SNAPSHOT.jar ftsm_p4a 8080 …` 启动；然后 `curl -fsS 127.0.0.1:8080/api/products`、`curl -fsS 127.0.0.1:8080/api/seckill/events` | 启动成功（日志中没有 `Not a managed type`）；两个接口都返回 JSON 数组 | PR | 自动 | 是 |
| A5 | 不再有旧的命令写法 | — | `$REPO` | `grep -rn 'cd backend && ./mvnw' README.md HANDOFF.md loadtest scripts docs/phases/P4a.md; test ! -e backend/mvnw` | grep 的结果只出现在本文件的说明文字里；`backend/mvnw` 不存在 | PR | 自动 | 是 |
| A6 | 镜像构建 | Docker | `$REPO` | `docker build -f backend/Dockerfile -t ftsm-backend:p4a .` | 成功；`docker run --rm --entrypoint ls ftsm-backend:p4a /app` 能看到 `app.jar` | PR | 自动 | 是 |
| A7 | 容器内 e2e | §7 | `$REPO` | §7 的完整脚本 | 退出码 0，8/8 | PR | 自动 | 是 |
| A8 | 移动的文件内容未改 | — | `$REPO` | `git diff -M100% --stat origin/main...HEAD -- catalog-core` | 每一行都显示为 `=>` 的 rename，没有内容改动（统计为 `0 insertions/0 deletions`） | PR | 自动 | 是 |
| A9 | 清理 | — | `$REPO` | `"${DC[@]}" down -v` | 只删除本阶段的项目 | — | 自动 | 否 |

## 10. 升级、重跑与恢复

- 没有数据变更。
- 重跑：所有构建都可以重复执行；容器验收使用独立的项目名。
- 恢复：revert 本阶段的 PR 即可回到单模块（`git mv` 是可逆的）。
- 清理范围：只针对 `ftsm-p4a-acc` 项目和 `$P4A_TMP`。

## 11. 完成判定与 PR

- **合并**：A1–A8 全部通过。
- **draft**：任一项失败或未执行。
- **外部待办**：无。
- **标题**：`P4a: split catalog-core module, move Maven Wrapper to root (v0.8.0)`
- **描述**：沿用 P0 的模板，另加 “Moved files (old → new)” 表和 “Test counts before/after”。

## 12. 独立 agent prompt

见 [`../agent-prompts/P4a.md`](../agent-prompts/P4a.md)。
