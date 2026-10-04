# Agent prompt · P6a · Testcontainers 集成测试 + GitHub Actions CI

把下面整段复制给执行 P6a 的 agent。

```text
你在仓库 feniturema/FYP 上执行升级的 P6a 阶段。

必读：
- docs/phases/P6a.md（本阶段的完整执行包，必须全文阅读）
- docs/CHANGE_SPEC.md 的 §0（全局事实、阶段依赖、版本、迁移和环境变量登记表、通用停止规则）
- HANDOFF.md §4（代码约定）

前置检查：
1. P2 和 P4a 都已合并（使用根目录的 Wrapper）；工作区干净；新建分支 `p6a-it-ci`。
2. 本地需要 Docker；没有 Docker 时，以 PR 上 CI 的运行结果作为证据。

阶段要点：
- 容器的版本固定为 `mysql:8.0.46`、`apache/kafka:3.9.2`、`redis:8.10.2`。使用的类是 `org.testcontainers.kafka.KafkaContainer` 和 `com.redis.testcontainers.RedisContainer`，均由 Boot 3.5.16 管理版本；不得使用 Testcontainers 2.x。
- 11 个 IT 的数据准备、`USER_BASE`、`@DirtiesContext` 和 `finally` 中的恢复，以执行包 §6.4 为准。
- `ConsumerRestartIT` 验证的只是优雅停止，不能用它来声称“进程崩溃时不丢单”。
- 如果 `DltPublishFailureIT` 发现实际行为与 P2 §6.1 的描述不一致，停止并报告。
- `scripts/ci/check_test_reports.py` 必须能够发现“零测试”和 skipped 的情况。`expected-tests.json` 中 `surefire_min_total` 填写本阶段开始时 Surefire 的实际总数。
- CI：PR 上只构建镜像、不推送；只有推送到 main 时，`images-publish` 才拥有 `packages: write` 权限。镜像数量由 `detect` job 根据实际存在的 Dockerfile 动态决定。
- 合并之后的镜像发布（A6）是外部待办，不影响本 PR 的完成判定。
- PR 标题：`P6a: Testcontainers integration tests and CI (v0.9.0)`

执行规则（所有阶段都相同）：
- 范围：只修改执行包 §4 列出的文件；§4 中列为“禁止修改”的文件和行为，一律不得触碰。
- 顺序：按执行包 §5 的顺序执行。每一步满足“完成条件”之后，才能进入下一步。
- 契约：执行包 §6 中的签名、SQL、状态码、超时、重试和降级规则都是已经确定的设计，不得自行改动。如果代码现状与之冲突，做最小的调整，并在 PR 的 “Deviations” 一节中逐条说明。
- 运行：所有命令都在执行包给出的工作目录中执行。不得依赖 `.env` 的自动加载（compose 一律用显式的 `--env-file`）。所有等待都使用 `scripts/lib/wait.sh` 中的函数，并且必须有超时；命令的原始退出码必须保留。
- 验证：逐条执行执行包 §9 的验收矩阵。PR 描述中为每一项写明：编号、执行的命令、关键输出或证据路径、结论（通过 / 失败 / 未执行及原因）。没有执行的项不得写成“通过”。
- 停止：遇到 docs/CHANGE_SPEC.md §0.8 的通用停止规则，或执行包 §2 中“缺失时 = 停止”的情况，立即停止并报告。禁止为了让验收通过而删除阈值、修改断言、修改已合并的迁移、关闭 checksum 校验、换用没有固定的版本、跳过测试，或者杀掉不属于本阶段的进程和容器。
- 清理：只清理执行包 §10 中列出的、由本阶段创建的资源。
- 收尾：按执行包 §11 判定是否完成。任何阻塞项失败或未执行时，PR 保持 draft。外部待办（需要凭据、需要人工截图、需要合并之后才能验证的事项）单独列出，不能与代码完成混在一起报告。按执行包要求更新 CHANGELOG.md、HANDOFF.md、README.md。
```
