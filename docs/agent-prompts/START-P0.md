# 最终开工 Prompt · P0

将下面整段复制给 Claude。它只负责 P0；P0 合并后，再单独使用对应阶段的 prompt 开始 P1。

```text
你在 GitHub 仓库 feniturema/FYP 上执行升级计划的 P0 阶段：基线 tag、Maven Wrapper、Flyway V1、k6 冒烟。

先读完以下文件，再执行任何修改：
1. docs/phases/P0.md（P0 完整执行包，按它的文件清单、顺序、契约和验收矩阵执行）
2. docs/CHANGE_SPEC.md §0（全局事实、阶段顺序、版本、迁移、环境变量、端口、停止规则）
3. HANDOFF.md §4（代码约定）
4. docs/agent-prompts/P0.md（P0 阶段约束）

硬性前置条件：
- 执行 `git fetch origin main`。
- `origin/main` 的 CHANGELOG.md 必须包含 `## [v0.4.6]`，并且 `origin/main:docs/phases/P0.md` 存在。文档分支没有合并时立即停止；不得自行合并、cherry-pick 或绕过这个条件。
- `git status --porcelain` 必须为空。工作区不干净时立即停止；不得 stash、reset、checkout --、删除或覆盖用户改动。
- JDK 21.x、Maven 3.9.x、Python >=3.10、curl、sha256sum、tar、GNU/coreutils timeout 必须存在。
- 端口、Docker/MySQL/Redis、磁盘和内存按 docs/phases/P0.md §2 检查。端口被占用或资源不足时停止，不得杀掉不属于本阶段的进程或容器。
- 从最新的 `origin/main` 创建分支 `p0-baseline-flyway`。只实施 P0，不开始 P1 或其他阶段。

实施规则：
- 严格按 docs/phases/P0.md §5 的顺序执行；每一步完成条件满足后才能进入下一步。
- 只修改 P0 §4 列出的文件。禁止修改业务 Java、frontend、现有 e2e 脚本、Dockerfile、.env.example 和用户已有资源。
- `V1__baseline.sql` 必须由真实 MySQL 8.0.x 通过导出脚本生成，不能手写、不能用 H2 生成、不能修改已合并的迁移。
- 没有 Docker 时只能使用 P0 §6.3 的独立临时 MySQL/Redis 数据目录和端口；不得使用或修改系统 MySQL 服务、系统数据目录或系统 root 账户。
- `wait_cmd` 必须使用 coreutils `timeout` 限制每次尝试；缺少 `--`、空命令或非正 timeout 返回 2。所有 readiness 等待都必须有明确超时。
- 所有下载都必须校验固定 checksum；不得增加跳过校验、浮动版本或“下载失败继续”的开关。
- k6 只执行 smoke 规模，结果放入 `loadtest/results/_smoke/`；不要把 smoke 结果冒充正式性能结论。
- 远端 baseline tag 必须用 peeled 引用核对：`refs/tags/v0.4.2-baseline^{}`。
- 如果代码现状与规格冲突，做最小必要调整，并在 PR 的 Deviations 中逐条记录；不要擅自改设计、阈值、状态码、迁移编号或验收断言。

验证和收尾：
- 逐项执行 P0 §9 的 A1–A19。每项记录实际命令、关键输出、证据文件、状态（通过 / 失败 / 未执行）和原因；未执行不得写成通过。
- 任一阻塞验收失败、依赖缺失、checksum 失败、schema 不一致或清理不完整时，PR 保持 draft，并报告具体阻塞点。
- 所有临时容器、进程、worktree、数据目录和日志都必须按 §10 清理；只清理本阶段创建的资源。
- 运行 `git diff --check`，检查改动范围和密钥扫描；确认没有用户文件被改动。
- 按 P0 §11 更新 CHANGELOG.md、HANDOFF.md、README.md，提交 PR，标题使用：
  `P0: baseline tag, Maven Wrapper, Flyway V1, k6 smoke (v0.5.0)`
- PR 描述必须包含验收矩阵、证据路径、未执行项、失败项、Deviations 和清理结果。不要自动合并 PR，不要开始 P1。
```
