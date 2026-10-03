# Agent prompt · P6b · ShedLock、探针、OpenTelemetry、Grafana、Kubernetes

把下面整段复制给执行 P6b 的 agent。

```text
你在仓库 feniturema/FYP 上执行升级的 P6b 阶段。

必读：
- docs/phases/P6b.md（本阶段的完整执行包，必须全文阅读）
- docs/CHANGE_SPEC.md 的 §0（全局事实、阶段依赖、版本、迁移和环境变量登记表、通用停止规则）
- HANDOFF.md §4（代码约定）

前置检查：
1. P6a、P4b、P5a 都已合并（`flyway_schema_history` 中最高版本为 3）；工作区干净；新建分支 `p6b-ops`。
2. 需要 Docker；kind、kubectl、kubeconform 用 `scripts/p6b/install_tools.sh` 安装，并按 sha256 校验。

阶段要点：
- ShedLock 使用 6.10.0（7.x 面向 Spring 7）。锁名和各项时间参数以执行包 §6.1 的表为准；`OutboxRelay` **不得**加锁。
- 探针：readiness 包含 db 和 redis，不包含 kafka 和 mcp；两个探针路径允许匿名 GET。
- OTel agent 2.32.0 必须用 `ADD --checksum` 下载。agent 只负责 traces，其他输出全部关掉：`OTEL_METRICS_EXPORTER=none`、`OTEL_LOGS_EXPORTER=none`、`OTEL_INSTRUMENTATION_MICROMETER_ENABLED=false`。指标由 `micrometer-registry-otlp` 推送。
- traceparent 写进 outbox，relay 发送时在恢复出来的父 context 内进行；没有 agent 时这些调用都是 no-op。
- 指标名称以 `check_metrics.py` 实际查到的为准；与预期不一致时，同时修改 dashboard 和文档。
- kind 默认 StorageClass 的 provisioner 必须实测，不是 `rancher.io/local-path` 时使用 local-path overlay。
- HPA 验收只需要提交证据，不要求一定扩容到某个数量。
- 无法运行 kind 时：A9 的静态校验照常执行，A10–A13 标“未执行”，PR 保持 draft。
- PR 标题：`P6b: ShedLock, probes, OpenTelemetry, Grafana, Kubernetes manifests (v0.13.0)`

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
