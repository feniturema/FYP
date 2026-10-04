# Agent prompt · P4b · MCP server + Spring AI 助手 + SSE + Resilience4j

把下面整段复制给执行 P4b 的 agent。

```text
你在仓库 feniturema/FYP 上执行升级的 P4b 阶段。

必读：
- docs/phases/P4b.md（本阶段的完整执行包，必须全文阅读）
- docs/CHANGE_SPEC.md 的 §0（全局事实、阶段依赖、版本、迁移和环境变量登记表、通用停止规则）
- HANDOFF.md §4（代码约定）

前置检查：
1. P4a 和 P6a 都已合并；工作区干净；新建分支 `p4b-assistant-mcp`。
2. 自动化验收**不需要**任何 key。真实 LLM 的验收（E1–E3）需要人提供 key，不阻塞合并。

阶段要点（这些结论来自仓库外的实验，见执行包 §6.1）：
- `spring.ai.chat.client.enabled=false` 必须一直设置；`ChatClient` 由我们用 `ObjectProvider<ChatModel>` 自己构建。
- `LLM_PROVIDER` 默认为 none。provider 为 deepseek 但 key 为空时，由 EnvironmentPostProcessor 改为 none；provider 是其他值时启动失败。
- 不引入 `spring-ai-starter-mcp-client`（server 不可达时它会导致启动失败）。改用自己实现的 `McpToolsProvider`：延迟连接、用 ReentrantLock 保证线程安全、工具列表缓存 5 分钟、失败后 30 s 重试、`destroy` 时关闭。
- 每次请求都显式设置完整的 system 文本（`BASE_SYSTEM` + 可选的 note），**不得**使用 `defaultSystem`，因为 `system()` 会覆盖默认值。
- 流式接口的鉴权：**先**写真实 Tomcat 的 `AssistantStreamSecurityIT`，只有当“合法 token”用例返回 403 时才修改 `JwtAuthFilter`；不得用 MockMvc 来证明这一点；不得使用 `dispatcherTypeMatchers(ASYNC).permitAll()`。
- 熔断和限流的测试使用可控的 stub ChatModel，不调用付费服务。
- 工具记录覆盖全部 6 个工具，评测集的 `expected_tools` 只能取自这 6 个。
- 验收环境必须按执行包 §7.6 设置 `SEED_ENABLED=true`、固定的临时管理员账号，并用 `scripts/p4b/get_test_token.py` 通过 `/api/auth/login` 生成 `TOKEN`；不得使用空 token、手写 JWT、邮件 OTP 或宿主机数据库用户。
- `mcp-server` 的运行配置保持 `spring.flyway.enabled=false`。`McpServerIT` 按执行包 §7.7 先用 test-scoped Flyway 对 Testcontainers MySQL 执行 backend 的 V1–V3，再通过 `@DynamicPropertySource` 启动 mcp-server；不得把生产配置改成自动迁移。
- 升级注意：已有的 `.env` 必须加上 `LLM_PROVIDER=deepseek`，写进 CHANGELOG 和 README。
- PR 标题：`P4b: MCP server, Spring AI assistant with SSE and Resilience4j (v0.10.0)`

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
