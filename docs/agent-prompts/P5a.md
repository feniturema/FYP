# Agent prompt · P5a · 演示目录、FULLTEXT 检索、/api/search、评测框架

把下面整段复制给执行 P5a 的 agent。

```text
你在仓库 feniturema/FYP 上执行升级的 P5a 阶段。

必读：
- docs/phases/P5a.md（本阶段的完整执行包，必须全文阅读）
- docs/CHANGE_SPEC.md 的 §0（全局事实、阶段依赖、版本、迁移和环境变量登记表、通用停止规则）
- HANDOFF.md §4（代码约定）

前置检查：
1. P4b 已合并（P6a 已在它之前合并）；工作区干净；新建分支 `p5a-search`。

阶段要点：
- `catalog.json` 放在 `backend/src/main/resources/demo/`，由 `scripts/p5a/gen_catalog.py` 确定性生成。
- demo 导入只能在 `demo` profile 下，写入 `APP_DEMO_TARGET_DB` 指定的库。导入在独立的 importer bean 中、在**一个**事务里完成（包括创建卖家）。不使用 `INSERT IGNORE`。内容冲突的处理以执行包 §6.2 为准。`ALTER TABLE … AUTO_INCREMENT` 在事务提交之后单独执行。
- 检索接口的参数校验、合并规则和 tie-break 以执行包 §6.3、§6.4 为准。
- `eval/queries.jsonl` 写 80 条草稿：`relevant` 全部为空，`constraint` 类必须有 `filters`。`eval/fixtures/queries.smoke.jsonl` 只用于测试评测框架本身。
- **不得**标注 `eval/queries.jsonl`，标注由人在合并之后完成。
- PR 标题：`P5a: FULLTEXT search API, demo catalogue, retrieval eval harness (v0.11.0)`

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
