# Agent prompt · P5b · 向量检索、RRF、LLM 重排、正式跑分

把下面整段复制给执行 P5b 的 agent。

```text
你在仓库 feniturema/FYP 上执行升级的 P5b 阶段。

必读：
- docs/phases/P5b.md（本阶段的完整执行包，必须全文阅读）
- docs/CHANGE_SPEC.md 的 §0（全局事实、阶段依赖、版本、迁移和环境变量登记表、通用停止规则）
- HANDOFF.md §4（代码约定）

前置检查：
1. P5a 已合并；`main` 上已有 `eval: label queries` 这个提交，并且每条查询的 `relevant` 都不为空。否则停止。
2. **人工决策 D2**：本 prompt 中必须出现 `EMBEDDING_PROVIDER=openai` 或 `EMBEDDING_PROVIDER=ollama`；没有就停止并询问，不得自行选择。
3. 凭据：openai 需要人以环境变量形式提供 `OPENAI_API_KEY`；ollama 需要能运行 `ollama/ollama:0.35.1`。缺少时，可以完成代码和离线测试，但 PR 保持 draft。
4. 工作区干净；新建分支 `p5b-hybrid-search`。

本阶段的 D2 = （由人填写：openai | ollama）

阶段要点：
- 只引入 `spring-ai-redis-store` 这个库模块，**不得**引入 `spring-ai-starter-vector-store-redis`；VectorStore 由 `CatalogVectorStoreHolder` 延迟创建，不注册为 bean。
- 在 yml 中显式把 `spring.ai.model.image`、`audio.speech`、`audio.transcription`、`moderation` 设为 none；embedding 的守卫加在 EPP 中。
- Redis 的能力用 `FT._LIST` 在运行时检查，不根据镜像名推断。
- 过滤条件只能通过 `FilterExpressionBuilder` 构造。RRF 的 tie-break 以及数据库复查以执行包 §6.5、§6.6 为准。
- 索引同步在处理时读取数据库的当前状态（因此能处理乱序和重复的事件），配合 dirty 集合、定时对账和回填。
- `NoEmbeddingStartupIT` 必须启动**完整应用**。
- **不得修改** `eval/queries.jsonl`。正式跑分的结果如实提交，不设分数门槛。
- PR 标题：`P5b: vector recall, RRF, LLM rerank, retrieval evaluation (v0.12.0)`

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
