# P4b · MCP server + Spring AI 助手 + SSE + Resilience4j

> 版本：v0.10.0 · 前置：P4a、P6a · 下一阶段：P5a
> 全局约定见 [`../CHANGE_SPEC.md`](../CHANGE_SPEC.md) §0。证据标记含义同 P0：**[实验]** / **[源码]** / **[未验证]**。
> 本阶段的关键事实都在仓库外用 Boot 3.5.16 + Spring AI 1.1.8 做过实验 **[实验]**，结论汇总在 §6.1。

## 1. 目标与非目标

**问题**：现在的助手只是把关键词匹配到的商品拼进 prompt，没有工具调用，也不支持流式输出；没有 key 时虽然能启动，但无法扩展。

| | 实施前 | 实施后 |
| --- | --- | --- |
| 模型接入 | 手写 WebClient 调 `/chat/completions` | Spring AI 1.1.8 `DeepSeekChatModel`；`ChatClient` 由我们自己构建 |
| 工具 | 无 | mcp-server 提供 5 个只读工具（streamable HTTP，`/mcp`）+ 本地工具 `my_orders`（userId 从 ToolContext 取） |
| 输出 | 一次性 JSON | `POST /api/assistant/stream` 返回 SSE；`POST /api/chat` 保留，改为把流聚合后返回 |
| 保护 | 无 | Resilience4j：限流 `llm` 20/s，熔断 `llm` |
| 没有 key | 返回 “not configured” | 行为不变，并且三种无 key 的配置都保证能启动（§6.2） |
| MCP 不可用 | — | backend 照常启动；助手只能使用本地工具，并如实告知用户 |

**非目标**：向量检索（P5b）；`/api/search` 和 FULLTEXT（P5a）；不新增数据库迁移；不修改 Marketplace 页面。

## 2. 前置条件

| 类别 | 要求 | 缺失时 |
| --- | --- | --- |
| 已合并阶段 | P4a（多模块）、P6a（`AbstractIntegrationTest`、Failsafe、CI） | 停止 |
| 工具 | Docker（Testcontainers 和 compose 验收）、JDK 21、Node 20、Python 3.10 | 没有 Docker 时：IT 与 compose 验收标“未执行”，PR 保持 draft |
| 端口 | `ftsm-p4b-acc` 项目：MySQL 53306、Redis 56379、Kafka 59092、后端 58080、mcp-server 58082（只绑定 127.0.0.1）、前端 58081 | 停止 |
| 凭据 | **自动化验收不需要任何 key**。真实 LLM 的验收（E1–E3）需要人提供 `LLM_API_KEY`，不阻塞合并 | — |
| 人工决策 | 不需要 | — |

## 3. 阶段输入与输出

**输入**：`ChatService`、`WebClientConfig`、`ChatbotController`（同步）、`ChatWidget.tsx`（一次性请求）、catalog-core（实体和仓库）、`AbstractIntegrationTest`。

**输出与契约**：

| 输出 | 契约 / 消费者 |
| --- | --- |
| 模块 `mcp-server`（端口 8081，`/mcp`，streamable HTTP） | P5b 会把 `search_*` 工具改为调用混合检索；P6b 负责部署 |
| `catalog-core/.../search/CatalogSearchService`（关键词检索，暂时沿用 LIKE）和 `SearchDtos` | P5a 会把它替换为 FULLTEXT 实现，**方法签名保持不变** |
| 工具名集合 `{search_products, search_secondhand_items, get_product_detail, get_stock, list_flash_sales, my_orders}` | 评测集的 `expected_tools` 只能取自这个集合 |
| 环境变量 `LLM_PROVIDER`（默认 none）、`DEEPSEEK_BASE_URL`、`MCP_SERVER_URL`、`MCP_CLIENT_ENABLED`、`MCP_DB_USERNAME`、`MCP_DB_PASSWORD`、`MCP_HOST_PORT` | 见 CHANGE_SPEC §0.7 的环境变量登记表 |
| `AssistantService.stream(Long userId, String conversationId, String message): Flux<String>` | 前端；P5b 不修改 |
| backend 的 `ObjectProvider<ChatModel>` 装配方式 | P5b 的 `LlmReranker` 复用同一个 `ChatModel` |

## 4. 逐文件清单

缩写：
- `J=backend/src/main/java/my/edu/ukm/ftsm/ecommerce`
- `T=backend/src/test/java/my/edu/ukm/ftsm/ecommerce`
- `M=mcp-server/src/main/java/my/edu/ukm/ftsm/ecommerce/mcp`
- `MT=mcp-server/src/test/java/my/edu/ukm/ftsm/ecommerce/mcp`
- `C=catalog-core/src/main/java/my/edu/ukm/ftsm/ecommerce`

| 操作 | 路径 | 职责 / 主要符号 | 验证 |
| --- | --- | --- | --- |
| 修改 | `pom.xml`（根） | `<module>mcp-server</module>`；`dependencyManagement` 导入 `org.springframework.ai:spring-ai-bom:1.1.8`（type pom，scope import）；属性 `resilience4j.version=2.4.0`，并为 `resilience4j-spring-boot3` 和 `resilience4j-reactor` 声明版本 | A1 |
| 新增 | `C/search/SearchDtos.java` | `ProductView`、`ItemView`、`StockView`、`FlashSaleView`、`ProductDetailView`（§6.6） | A2 |
| 新增 | `C/search/CatalogSearchService.java` | `List<ProductView> searchProducts(String keyword, BigDecimal maxPrice, String category, int limit)`；`List<ItemView> searchItems(String keyword, BigDecimal maxPrice, int limit)` | A2 |
| 修改 | `C/repository/SeckillEventRepository.java` | `List<SeckillEvent> findByProductIdAndStatus(Long productId, SeckillEvent.Status status)` | A2 |
| 新增 | `mcp-server/pom.xml` | §6.5 | A1 |
| 新增 | `mcp-server/Dockerfile` | 与 backend 的 Dockerfile 结构相同；runtime 阶段执行 `apt-get install -y --no-install-recommends curl`，供健康检查使用 | A4 |
| 新增 | `mcp-server/src/main/resources/application.yml` | §7.2 | A4 |
| 新增 | `M/McpServerApplication.java`、`M/ShopTools.java`、`M/ToolsConfig.java` | §6.5 | A2、A5 |
| 新增 | `MT/ShopToolsTest.java`、`MT/McpServerIT.java` | §8 | A2 |
| 修改 | `backend/pom.xml` | 新增 `spring-ai-starter-model-deepseek`、`resilience4j-spring-boot3`、`resilience4j-reactor`、`spring-boot-starter-aop`、`io.modelcontextprotocol.sdk:mcp`（版本由 Spring AI BOM 管理，为 0.18.3 **[实验]**）、`org.springframework.ai:spring-ai-mcp`（`SyncMcpToolCallbackProvider` 所在的模块）。**不引入** `spring-ai-starter-mcp-client`（它的自动配置在 server 不可达时会导致启动失败 **[实验]**） | A1 |
| 新增 | `J/config/LlmProviderEnvironmentPostProcessor.java`；`backend/src/main/resources/META-INF/spring.factories` | §6.2 | A2 |
| 新增 | `J/config/AssistantConfig.java` | bean：`ChatMemory`（使用 `BoundedChatMemoryRepository`）、`ToolCallRecorder` | A2 |
| 新增 | `J/service/BoundedChatMemoryRepository.java` | §6.4 | A2 |
| 新增 | `J/service/McpToolsProvider.java` | §6.3 | A2 |
| 新增 | `J/service/ToolCallRecorder.java` | §6.4 | A2 |
| 新增 | `J/service/OrderTools.java` | `@Tool(name="my_orders")` | A2 |
| 新增 | `J/service/AssistantService.java` | §6.4 | A2 |
| 新增 | `J/controller/AssistantController.java` | `POST /api/assistant/stream` | A2 |
| 新增 | `J/controller/AssistantDebugController.java` | `@Profile("dev")`；`GET /api/assistant/debug/tools`、`GET /api/assistant/debug/tool-calls?conversationId=` | A6 |
| 新增 | `J/config/DevFakeChatModelConfig.java` | `@Profile("dev")` + `@ConditionalOnProperty(name="app.assistant.fake-model", havingValue="true")`，提供 `FakeStreamingChatModel`：逐个输出 `["Hel","lo ","from ","fake"]`，每段间隔 300 ms。只用于验收流式输出，不涉及工具调用 | A8 |
| 修改 | `J/controller/ChatbotController.java` | 改为调用 `assistantService.stream(...)` 并聚合；返回的 `ChatResponse` 结构不变 | A2 |
| 修改 | `J/dto/ChatDtos.java` | `ChatRequest(@NotBlank @Size(max=2000) String message, @Pattern(regexp="^[A-Za-z0-9-]{1,64}$") String conversationId)` | A2 |
| 删除 | `J/service/ChatService.java`、`J/config/WebClientConfig.java` | 被替代 | A1 |
| 条件修改 | `J/security/JwtAuthFilter.java` | 只有在 `AssistantStreamSecurityIT` 的“合法 token”用例返回 403 时才修改（§6.7） | A2 |
| 修改 | `backend/src/main/resources/application.yml` | §7.1；删除 `app.llm.*` | A2 |
| 修改 | `backend/Dockerfile` | runtime 阶段安装 curl（供 compose 健康检查使用） | A4 |
| 新增 | `T/config/LlmProviderEnvironmentPostProcessorTest.java`、`T/service/McpToolsProviderTest.java`、`T/service/ToolCallRecorderTest.java`、`T/service/BoundedChatMemoryRepositoryTest.java`、`T/service/OrderToolsTest.java`、`T/service/AssistantServiceTest.java` | §8 | A2 |
| 新增 | `T/it/AssistantStartupIT.java`、`T/it/AssistantStreamSecurityIT.java`、`T/it/AssistantResilienceIT.java`、`T/it/AssistantToolFlowIT.java` | §8 | A2 |
| 修改 | `scripts/ci/expected-tests.json` | 加入上面 4 个 backend IT 和 `McpServerIT`（报告路径为 `mcp-server/target/failsafe-reports`，脚本需要支持多模块） | A3 |
| 修改 | `scripts/ci/check_test_reports.py` | 支持多个 reports 目录 | A3 |
| 修改 | `.github/workflows/ci.yml` | frontend job 增加 `npm test`。镜像矩阵会自动包含 mcp-server，因为 `detect` 只检查 Dockerfile 是否存在 | A3 |
| 修改 | `docker-compose.yml` | §7.3 | A4 |
| 新增 | `mysql/init/01-readonly-user.sh` | §6.8 | A9 |
| 修改 | `.env.example` | 删除 `LLM_BASE_URL`；新增 `LLM_PROVIDER=deepseek`（注释写明：不设置时默认为 none，助手关闭）、`DEEPSEEK_BASE_URL`、`MCP_DB_PASSWORD`（注释写明生成方式 `openssl rand -hex 24`）、`MCP_HOST_PORT` | — |
| 新增 | `frontend/src/services/sse.ts`、`frontend/src/services/sse.test.ts` | §6.9 | A3 |
| 修改 | `frontend/src/services/api.ts`、`frontend/src/features/chatbot/ChatWidget.tsx`、`frontend/src/types/index.ts` | 流式渲染 | A3、A8 |
| 修改 | `frontend/package.json`、`frontend/package-lock.json` | devDependency `vitest@2.1.9`（peerDependency 为 `vite ^5.0.0`，与当前的 vite 5 兼容 **[源码：npm 元数据]**）；script `"test": "vitest run"` | A3 |
| 修改 | `frontend/nginx.conf` | SSE 的 location（§7.4） | A8 |
| 新增 | `scripts/p4b/mcp_probe.py` | 只用 Python 标准库实现的 MCP streamable HTTP 客户端，执行 `initialize`、`tools/list`、`tools/call` | A5 |
| 新增 | `scripts/p4b/acceptance.sh` | 依次执行 A4–A10 | A4–A10 |
| 新增 | `scripts/p4b/sse_timing.py` | 只用标准库：发 POST 读取 SSE，记录每个 `token` 事件的到达时间，输出 JSON `{firstMs,lastMs,count}` | A8 |
| 新增 | `scripts/p4b/evidence/.gitkeep` | 验收证据目录 | — |
| 新增 | `eval/assistant_questions.jsonl`、`eval/run_assistant_eval.py`、`eval/results/.gitkeep` | §6.10 | E2 |
| 修改 | `README.md`、`HANDOFF.md`、`CHANGELOG.md` | v0.10.0；**升级注意**：已有的 `.env` 必须加上 `LLM_PROVIDER=deepseek`，否则助手会被关闭 | — |

**禁止修改**：迁移文件；`SeckillService` 相关的所有类；`Marketplace.tsx`；`SecurityConfig` 中现有的规则（只允许按 §7.5 新增）。

## 5. 实施顺序

| # | 任务 | 完成条件 | 持久状态 |
| --- | --- | --- | --- |
| 1 | 根 pom、catalog-core 的 search 包 | 编译通过 | 文件 |
| 2 | mcp-server 模块与 `McpServerIT` | `./mvnw -B -pl mcp-server -am verify` 通过 | 文件 |
| 3 | backend：EPP 及其单元测试 | 测试通过 | 文件 |
| 4 | **先写** `AssistantStreamSecurityIT`，但只写 controller 的最小骨架（AssistantService 用 mock） | 记录“合法 token”用例的结果，决定是否执行 §6.7 的修复 | 文件 |
| 5 | McpToolsProvider、ToolCallRecorder、BoundedChatMemoryRepository、OrderTools、AssistantService 及其单元测试 | 测试通过 | 文件 |
| 6 | 4 个 assistant 的 IT | 测试通过 | — |
| 7 | 删除 ChatService 和 WebClientConfig；修改 ChatbotController | `./mvnw -B verify` 通过 | 文件 |
| 8 | 前端：sse.ts 与测试、ChatWidget、nginx | `npm test && npm run build` 通过 | 文件 |
| 9 | compose、只读账号脚本、mcp_probe、acceptance.sh | A4–A10 通过 | `ftsm-p4b-acc` 项目 |
| 10 | CI 相关文件、eval、文档 | A3 通过 | — |
| 11（人工，可选） | 用真实 key 执行 E1–E3 | 记录结果 | — |

## 6. 实现契约

### 6.1 实验结论（Boot 3.5.16 + Spring AI 1.1.8，仓库外实验）

| 配置 | 结果 **[实验]** |
| --- | --- |
| 引入 deepseek starter，`spring.ai.model.chat` 不设置，也没有 key | **启动失败**：`DeepSeek API key must be set` |
| `spring.ai.model.chat=none`，但 `spring.ai.chat.client.enabled` 保持默认 | **启动失败**：`ChatClientAutoConfiguration` 仍然要求一个 `ChatModel` |
| `spring.ai.model.chat=none` + `spring.ai.chat.client.enabled=false` | 正常启动，不存在 ChatModel |
| `spring.ai.model.chat=deepseek`，key 为空 | **启动失败** |
| `spring.ai.model.chat=deepseek`，key 非空 | 正常启动，创建出 `DeepSeekChatModel` |
| 自定义 EPP：provider 为 deepseek 但 key 为空时，把 provider 改为 none | 正常启动，不存在 ChatModel |
| MCP client starter 指向一个不可达的 streamable 地址 | **启动失败**（`ClosedChannelException`） |
| MCP server starter 设置 `protocol: STREAMABLE` | 端点为 `/mcp`；Java 客户端能 `initialize`、`listTools` 并 `callTool` |
| 手工创建的 `McpSyncClient` 连接不可达地址 | 17 ms 内抛出 `RuntimeException` |
| `ChatClient.builder(m).defaultSystem("BASE")…prompt().system("NOTE")` | 发送给模型的消息里**只有 NOTE**，默认的 system 被覆盖 |
| Boot 3.5.16 / Spring Security 6.5，一个不保存 SecurityContext 的 `OncePerRequestFilter` 加上返回 `Flux` / `Mono` 的接口（真实 Tomcat，开启 `@EnableMethodSecurity`） | 合法 token 返回 200；没有 token 返回 403。**v0.4.0 时的 403 在当前版本下没有复现** |
| 用 MockMvc 的 `asyncDispatch` 测同样的情况 | 两种 filter 都是 200：MockMvc **不能**用来证明异步分派时的鉴权行为 |

### 6.2 LLM provider 的产品契约

`application.yml` 中的配置：

```yaml
spring.ai.model.chat: ${LLM_PROVIDER:none}
spring.ai.chat.client.enabled: false
```

`LlmProviderEnvironmentPostProcessor`（在 `META-INF/spring.factories` 中以 `org.springframework.boot.env.EnvironmentPostProcessor` 为 key 注册；它默认排在 Boot 的配置文件处理之后执行，所以能读到 yml 和环境变量里的值 **[实验]**）：

| `LLM_PROVIDER` | `LLM_API_KEY` | 最终行为 |
| --- | --- | --- |
| 未设置 / `none` | 任意 | 不创建 ChatModel；启动时打一行 INFO `assistant disabled (LLM_PROVIDER=none)`；两个接口都返回 “AI assistant is not configured yet.” |
| `deepseek` | 空或全是空白 | EPP 以最高优先级覆盖为 `spring.ai.model.chat=none`；启动时打 WARN `LLM_PROVIDER=deepseek but LLM_API_KEY is empty; assistant disabled`；行为同上 |
| `deepseek` | 非空 | 创建 `DeepSeekChatModel`，助手启用 |
| 其他任何值 | 任意 | EPP 抛出 `IllegalStateException("Unsupported LLM_PROVIDER: …; allowed: none, deepseek")`，**启动失败**（fail fast，避免配置拼错后静默不可用） |

`AssistantService` 不依赖 `ChatClient.Builder` bean，而是注入 `ObjectProvider<ChatModel>`，存在时自己用 `ChatClient.builder(chatModel)` 构建。

### 6.3 `McpToolsProvider`

```java
@Component
public class McpToolsProvider implements DisposableBean {
    private final ReentrantLock lock = new ReentrantLock();   // 不用 synchronized：JDK 21 上虚拟线程会被 pin
    private volatile McpSyncClient client;                    // 已连接时非 null
    private volatile ToolCallback[] cached = new ToolCallback[0];
    private volatile Instant cachedAt = Instant.EPOCH, lastFailure = Instant.EPOCH;

    /** Never throws. Never blocks startup. Returns cached MCP callbacks, or [] when unavailable. */
    public ToolCallback[] currentTools() { ... }
    public boolean available() { return client != null; }
    public void markBroken(Throwable t) { ... }               // 由 ToolCallRecorder 的包装在工具调用出现传输异常时调用
    @Override public void destroy() { ... closeGracefully() ... }
}
```

| 规则 | 值 |
| --- | --- |
| 何时连接 | 第一次调用 `currentTools()` 时（延迟连接）；启动时**不连接** |
| 并发 | `lock.tryLock(5, SECONDS)`。拿到锁后再检查一次状态（double-check）；拿不到锁就直接返回当前缓存，不阻塞请求 |
| 传输 | `HttpClientStreamableHttpTransport.builder(mcpUrl).endpoint("/mcp").connectTimeout(Duration.ofSeconds(3)).build()`（`connectTimeout` 和 `endpoint` 在 0.18.3 中存在 **[源码：javap]**） |
| 客户端 | `McpClient.sync(t).requestTimeout(Duration.ofSeconds(20)).initializationTimeout(Duration.ofSeconds(5)).build()`，然后 `initialize()` **[实验]** |
| 工具回调 | `SyncMcpToolCallbackProvider.builder().mcpClients(client).build().getToolCallbacks()` **[实验]** |
| 缓存 | 工具列表缓存 5 分钟（`app.assistant.mcp-tools-ttl`），过期后在锁内刷新 |
| 失败 | 连接、初始化、`listTools` 失败，或者工具调用时出现传输异常：调用 `closeGracefully` 并忽略异常，把 `client` 置为 null，`cached` 置为空，`lastFailure=now`，打 WARN `MCP server unavailable at {url}: {cause}` |
| 重试 | `now - lastFailure ≥ app.assistant.mcp-retry-interval`（默认 30 s）时才会再次尝试连接 |
| 关闭 | `destroy()` 中调用 `closeGracefully()` |
| 关闭开关 | `MCP_CLIENT_ENABLED=false` 时永远返回空数组，`available()` 永远为 false |

### 6.4 助手服务、工具记录与会话记忆

**system 提示**：每次请求都显式设置完整的 system 文本，**不使用** `defaultSystem`，因为 `prompt().system()` 会覆盖默认值 **[实验]**。

```java
static final String BASE_SYSTEM = """
    You are the FTSM Marketplace Assistant for UKM students. Use tools for any product, stock, price,
    flash-sale or order question; answer only from tool results; if a tool returns nothing, say you could
    not find it. Never reveal other users' data. Prices are in RM. Be concise.""";
static final String MCP_DOWN_NOTE = "\nCatalogue tools are temporarily unavailable: tell the user you cannot look up products right now.";
String system = BASE_SYSTEM + (mcp.available() ? "" : MCP_DOWN_NOTE);
```

**`AssistantService.stream`**：

```java
@RateLimiter(name = "llm")
@CircuitBreaker(name = "llm", fallbackMethod = "fallback")
public Flux<String> stream(Long userId, String clientConversationId, String message) {
    if (chatClient == null) return Flux.just(NOT_CONFIGURED);
    String conv = userId + ":" + Objects.requireNonNullElse(clientConversationId, "default");
    ToolCallback[] tools = recorder.wrap(conv, concat(mcp.currentTools(), ToolCallbacks.from(orderTools)));
    return chatClient.prompt()
            .system(BASE_SYSTEM + (mcp.available() ? "" : MCP_DOWN_NOTE))
            .user(message)
            .toolCallbacks(tools)
            .toolContext(Map.of("userId", userId, "conversationId", conv))
            .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conv))
            .stream().content()
            .timeout(Duration.ofSeconds(30));
}
Flux<String> fallback(Long userId, String c, String m, Throwable t) { return Flux.just(UNAVAILABLE_TEXT); }
```

- `chatClient` 在构造函数中创建：`ChatClient.builder(chatModel).defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build()).build()`；没有 `ChatModel` 时为 null。
- 记录范围是**全部**工具，包括本地的 `my_orders`：`recorder.wrap(conv, …)` 给每个回调套一层委托，在 `call(String input, ToolContext ctx)` 里记录 `(toolName, Instant)`；`getToolDefinition()` 和 `getToolMetadata()` 原样委托。如果委托抛出的是传输类异常（`McpTransportException` 或 `java.net.ConnectException` 及其子类），调用 `mcp.markBroken(t)` 后重新抛出。

**`ToolCallRecorder`**：

- 存储：Caffeine，最多 1000 个会话，`expireAfterWrite` 2 小时，每个会话最多 200 条记录。
- `List<ToolCallRecord> calls(String conv)`。

**`BoundedChatMemoryRepository implements ChatMemoryRepository`**：

- 存储：Caffeine，最多 1000 个会话，`expireAfterAccess` 2 小时。
- 外层由 `MessageWindowChatMemory.builder().chatMemoryRepository(repo).maxMessages(10)` 包装 **[实验：编译通过]**。
- 只存在进程内存里，重启后丢失；多副本之间不共享（这是已知限制，写进 README）。

**会话隔离**：

- 服务端拼接 `conv = userId + ":" + clientId`，客户端无法伪造他人的前缀。
- `clientId` 由 DTO 的正则约束为 `^[A-Za-z0-9-]{1,64}$`。
- 调试接口只在 `dev` profile 下注册，并且只返回以 `<当前 userId>:` 开头的会话；对其他用户的会话返回 404。

### 6.5 mcp-server

- `mcp-server/pom.xml`：parent 为 `ftsm-parent`；依赖 `catalog-core`、`spring-boot-starter-web`、`spring-ai-starter-mcp-server-webmvc`、`spring-boot-starter-data-jpa`、`spring-boot-starter-data-redis`、`spring-boot-starter-actuator`、`mysql-connector-j`（runtime）。test scope：`spring-boot-starter-test`、`spring-boot-testcontainers`、`org.testcontainers:mysql`、`org.testcontainers:junit-jupiter`、`com.redis:testcontainers-redis`、`org.flywaydb:flyway-core`、`org.flywaydb:flyway-mysql`。插件：`spring-boot-maven-plugin`、`maven-failsafe-plugin`。
- 启动类：

  ```java
  @SpringBootApplication(scanBasePackages = {"my.edu.ukm.ftsm.ecommerce.mcp", "my.edu.ukm.ftsm.ecommerce.search"})
  @EntityScan("my.edu.ukm.ftsm.ecommerce.model")
  @EnableJpaRepositories("my.edu.ukm.ftsm.ecommerce.repository")
  public class McpServerApplication { … }
  ```

- 工具的注册方式：`@Bean ToolCallbackProvider shopTools(ShopTools t) { return MethodToolCallbackProvider.builder().toolObjects(t).build(); }` **[实验]**。
- 工具（全部只读，描述用英文，并写明什么时候用）：

  | 工具 | 参数（JSON schema 由 `@ToolParam` 生成） | 返回 | 实现 |
  | --- | --- | --- | --- |
  | `search_products` | `keyword`（string，必填，1–200 个字符）、`maxPrice`（number，可选，≥ 0）、`category`（string，可选） | `List<ProductView>`，最多 10 条 | `CatalogSearchService.searchProducts` |
  | `search_secondhand_items` | `keyword`（必填）、`maxPrice`（可选） | `List<ItemView>`，只包含 ACTIVE 状态，最多 10 条 | `searchItems` |
  | `get_product_detail` | `productId`（integer，必填） | `ProductDetailView`；不存在时返回 `{"found":false}` | 商品 + `ReviewRepository` 的平均分与评价数 |
  | `get_stock` | `productId`（必填） | `StockView`：`{productId, totalStock, flashSale: null 或 {eventId, seckillPrice, remaining, endsAt}}`。`remaining` 读取 Redis 的 `seckill:stock:{eventId}`，key 不存在时为 null | |
  | `list_flash_sales` | `status`（可选：`PENDING` 或 `ACTIVE`；不传时两者都返回） | `List<FlashSaleView>` | |

- 参数非法时（例如 keyword 为空）：方法抛出 `IllegalArgumentException`，由 Spring AI 转成工具错误结果返回给模型（P4b 的验收会把这种输出记录下来）。

### 6.6 DTO（catalog-core `SearchDtos`）

```java
record ProductView(long id, String name, BigDecimal price, String category, int totalStock, String imageUrl) {}
record ItemView(long id, String title, BigDecimal price, String category, String condition, String imageUrl) {}
record FlashSaleView(long eventId, long productId, String productName, BigDecimal seckillPrice, String status, Instant startTime, Instant endTime) {}
record StockView(long productId, int totalStock, FlashSaleStock flashSale) { record FlashSaleStock(long eventId, BigDecimal seckillPrice, Integer remaining, Instant endsAt) {} }
record ProductDetailView(boolean found, ProductView product, String description, double averageRating, long reviewCount) {}
```

### 6.7 流式接口的鉴权

- 只读取 JWT，不保存 SecurityContext 的现状，在当前版本下实验结果为 200 **[实验]**。因此是否修改 `JwtAuthFilter` 由 `AssistantStreamSecurityIT` 决定：这个测试使用真实 Tomcat、真实的 `SecurityConfig` 和真实的 `JwtAuthFilter`；**不得**用 MockMvc 代替 **[实验]**。
- 测试用例：
  1. 合法 token → 200，`Content-Type: text/event-stream`，响应体包含 `event:token`；
  2. 没有 token → 403；
  3. 签名伪造的 token → 403；
  4. 已过期的 token → 403；
  5. 合法 token 调用 `POST /api/chat` → 200。
- 如果用例 1 返回 403：在 `JwtAuthFilter` 中保存 context（创建一个空 context，设置认证信息后放入 `SecurityContextHolder`，再调用 `new RequestAttributeSecurityContextRepository().saveContext(...)`），并在 `SecurityConfig` 中显式设置 `.securityContext(sc -> sc.securityContextRepository(new RequestAttributeSecurityContextRepository()))`。PR 里要贴出修改前后用例 1 的结果。
- **不得**使用 `dispatcherTypeMatchers(ASYNC).permitAll()`。

### 6.8 只读数据库账号（`mysql/init/01-readonly-user.sh`）

```bash
#!/bin/bash
set -euo pipefail
: "${MCP_DB_PASSWORD:?required}"
[[ "$MCP_DB_PASSWORD" =~ ^[A-Za-z0-9._~-]{24,128}$ ]] || { echo "MCP_DB_PASSWORD must match ^[A-Za-z0-9._~-]{24,128}\$" >&2; exit 1; }
[[ "$MYSQL_DATABASE" =~ ^[A-Za-z0-9_]{1,64}$ ]] || { echo "bad MYSQL_DATABASE" >&2; exit 1; }
MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot <<SQL
CREATE USER IF NOT EXISTS 'ftsm_ro'@'%' IDENTIFIED BY '${MCP_DB_PASSWORD}';
ALTER USER 'ftsm_ro'@'%' IDENTIFIED BY '${MCP_DB_PASSWORD}';
GRANT SELECT ON \`${MYSQL_DATABASE}\`.* TO 'ftsm_ro'@'%';
SQL
```

字符集白名单保证密码不可能逃逸出 SQL 字符串字面量；root 密码通过 `MYSQL_PWD` 传入，不出现在进程参数里。如果数据卷是在 P4b 之前创建的，需要手工执行一次：

```bash
docker compose exec mysql bash /docker-entrypoint-initdb.d/01-readonly-user.sh
```

### 6.9 前端 SSE 解析（`frontend/src/services/sse.ts`）

```ts
export interface SseEvent { event: string; data: string }
export function createSseParser(onEvent: (e: SseEvent) => void): { push(chunk: Uint8Array): void; end(): void }
export async function streamAssistant(message: string, conversationId: string,
  handlers: { onToken(t: string): void; onDone(): void; onError(msg: string): void }, signal: AbortSignal): Promise<void>
```

解析规则：

- 用 `TextDecoder('utf-8')` 加 `{ stream: true }` 解码，正确处理一个多字节字符被拆在两个 chunk 之间的情况。
- 行结束符同时支持 `\r\n`、`\n`、`\r`；`\r\n` 被拆在两个 chunk 之间时，只算一次换行。
- 以 `:` 开头的行是注释，忽略。
- `data:` 后面紧跟的那一个空格要去掉；同一个事件里有多行 `data:` 时用 `\n` 拼接。
- 遇到空行就派发一个事件；没有 `event:` 字段时，事件名默认为 `message`。
- `end()` 时丢弃最后一个还没遇到空行的事件。

`streamAssistant` 的行为：

- 用 `fetch` 发 POST，带上 `Authorization` 头；
- 401 时调用 `logout()` 并跳转到 `/login`；
- 非 2xx 时调用 `onError`；
- 收到 `event: token` 调用 `onToken`，`done` 调用 `onDone`，`error` 调用 `onError`；
- 调用方 `abort()` 时不触发 `onError`（捕获 `AbortError`）；
- 流在没有收到 `done` 的情况下中途结束，调用 `onError('connection closed')`。

`ChatWidget`：

- 先追加一个空的 assistant 气泡，再逐段写入；
- 会话 id 用 `crypto.randomUUID()` 生成，存在组件 state 里；
- 组件卸载时 `abort()`；
- 等待响应期间禁用发送按钮。

### 6.10 评测

`eval/assistant_questions.jsonl`：

- 25 条，格式 `{"id","q","lang":"en|ms|zh","expected_tools":[…]}`；
- `expected_tools` 只能取自 §3 的 6 个工具名；
- 每个工具至少 3 条，多工具组合 3 条，闲聊 3 条（`expected_tools` 为 `[]`）。

`eval/run_assistant_eval.py`：

- 参数：`--base`、`--token`（必填）、`--out`；
- 依赖后端以 `dev` profile 运行；
- 对每个问题生成新的 conversationId，读取流，记录首个 token 的时间（TTFT）和总耗时，然后调用调试接口取回实际调用过的工具；
- 统计：工具集合完全匹配的比例、至少命中一个期望工具的比例、TTFT 的 p50 和 p95；
- 遇到不在允许集合里的工具名时报错退出。

## 7. 配置与运行

### 7.1 backend 的 `application.yml`

```yaml
spring:
  ai:
    model: { chat: '${LLM_PROVIDER:none}' }
    chat: { client: { enabled: false } }
    deepseek:
      api-key: ${LLM_API_KEY:}
      base-url: ${DEEPSEEK_BASE_URL:https://api.deepseek.com}     # 不带 /v1
      chat: { options: { model: '${LLM_MODEL:deepseek-v4-flash}', temperature: 0.3 } }
  mvc: { async: { request-timeout: 60s } }
app:
  assistant:
    mcp-url: ${MCP_SERVER_URL:http://localhost:8081}
    mcp-enabled: ${MCP_CLIENT_ENABLED:true}
    mcp-retry-interval: 30s
    mcp-tools-ttl: 5m
    fake-model: false
resilience4j:
  ratelimiter: { instances: { llm: { limit-for-period: 20, limit-refresh-period: 1s, timeout-duration: 0 } } }
  circuitbreaker: { instances: { llm: { sliding-window-size: 20, minimum-number-of-calls: 10, failure-rate-threshold: 50, wait-duration-in-open-state: 30s } } }
management: { endpoints: { web: { exposure: { include: 'health,info,circuitbreakers' } } } }
```

### 7.2 mcp-server 的 `application.yml`

```yaml
server: { port: 8081 }
spring:
  application: { name: ftsm-mcp-server }
  datasource:
    url: ${DB_URL:jdbc:mysql://localhost:3306/ftsm_ecommerce?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC}
    username: ${MCP_DB_USERNAME:ftsm_ro}
    password: ${MCP_DB_PASSWORD:}
  jpa: { hibernate: { ddl-auto: validate }, open-in-view: false }
  flyway: { enabled: false }
  data: { redis: { host: '${REDIS_HOST:localhost}', port: '${REDIS_PORT:6379}' } }
  ai: { mcp: { server: { name: ftsm-shop, version: 1.0.0, type: SYNC, protocol: STREAMABLE } } }
management: { endpoints: { web: { exposure: { include: health } } } }
```

### 7.3 compose 的改动与地址

| 服务 | 容器内地址 | 宿主机地址 |
| --- | --- | --- |
| backend → mcp-server | `MCP_SERVER_URL=http://mcp-server:8081` | — |
| mcp-server | 8081 | `127.0.0.1:${MCP_HOST_PORT:-18082}:8081`，只绑定本机，供 Inspector 和探测脚本使用 |

- backend 新增 healthcheck：`curl -fsS http://localhost:8080/actuator/health || exit 1`，`interval 10s`，`retries 30`。
- mcp-server：`depends_on: {backend: {condition: service_healthy}, redis: {condition: service_healthy}}`。原因是 backend 健康就意味着 Flyway 迁移已经完成，schema 已就绪；mcp-server 使用 `ddl-auto: validate`，如果 schema 不完整会直接失败退出，配合 `restart: on-failure` 重试。
- mysql 新增挂载 `./mysql/init:/docker-entrypoint-initdb.d:ro`，以及环境变量 `MCP_DB_PASSWORD: ${MCP_DB_PASSWORD}`。
- backend 的 `environment` 中新增（env 文件只用于 compose 的变量替换，**不会**自动进入容器，所以每个变量都要显式写出来）：
  - `SPRING_PROFILES_ACTIVE: ${SPRING_PROFILES_ACTIVE:-prod}`（原来写死为 prod）
  - `LLM_PROVIDER: ${LLM_PROVIDER:-none}`
  - `DEEPSEEK_BASE_URL: ${DEEPSEEK_BASE_URL:-https://api.deepseek.com}`
  - `MCP_SERVER_URL: http://mcp-server:8081`
  - `MCP_CLIENT_ENABLED: ${MCP_CLIENT_ENABLED:-true}`
  - `APP_ASSISTANT_FAKEMODEL: ${APP_ASSISTANT_FAKEMODEL:-false}`
  
  同时删除 `LLM_BASE_URL`。
- mcp-server 的 `environment`：
  - `DB_URL`（指向 `mysql:3306/${DB_NAME:-ftsm_ecommerce}`）
  - `MCP_DB_USERNAME: ftsm_ro`
  - `MCP_DB_PASSWORD: ${MCP_DB_PASSWORD}`
  - `REDIS_HOST: redis`

### 7.4 nginx

```nginx
location /api/assistant/stream {
    proxy_pass http://backend:8080/api/assistant/stream;
    proxy_http_version 1.1;
    proxy_set_header Connection "";
    proxy_set_header Host $host;
    proxy_set_header Authorization $http_authorization;
    proxy_buffering off;
    proxy_cache off;
    proxy_read_timeout 300s;
}
```

这一段要放在 `location /api/` 之前。

### 7.5 安全规则

- `/api/assistant/**` 沿用 `anyRequest().authenticated()`，不需要新增规则。
- 调试接口只在 `dev` profile 下注册（controller 上加 `@Profile("dev")`）。

### 7.6 验收环境

做法同 P2 §7.4：项目名 `ftsm-p4b-acc`，使用显式的 `--env-file`，端口按 §2，后端日志持续写入文件。env 文件中包含 `MCP_DB_PASSWORD=$(openssl rand -hex 24)`、`LLM_PROVIDER` 不设置，以及 `SPRING_PROFILES_ACTIVE=dev`（用于启用调试接口）。

## 8. 测试清单（自动化测试全部离线、确定，不需要真实的模型）

| 类 | 场景 |
| --- | --- |
| `LlmProviderEnvironmentPostProcessorTest` | 未设置 → none；`none` → none；`deepseek` 加空 key → 覆盖为 none 并输出 WARN；`deepseek` 加 key → 保持 deepseek；`foo` → 抛出 `IllegalStateException` |
| `McpToolsProviderTest` | 不可达地址（`http://127.0.0.1:1`）→ 返回空数组，不抛异常；重试间隔内的第二次调用不再连接（计数器为 1）；20 个虚拟线程同时调用只连接 1 次（连接工厂注入为可计数的 mock）；`markBroken` 之后缓存被清空；`destroy` 调用了 `closeGracefully` |
| `ToolCallRecorderTest` | 能记录 MCP 工具和本地工具；会话隔离；超过 200 条时截断；传输异常会触发 `markBroken` |
| `BoundedChatMemoryRepositoryTest` | 超过 1000 个会话时淘汰；过期（注入 Ticker 控制时间）；按会话 id 读写 |
| `OrderToolsTest` | 只使用 ToolContext 中的 userId（验证 mock 的 `OrderService.listForBuyer(A)` 被调用）；工具定义的输入 schema 中没有任何属性 |
| `AssistantServiceTest` | 没有 ChatModel → 返回 NOT_CONFIGURED；MCP 不可用时 system 文本 = BASE + NOTE（用一个会记录 Prompt 的 stub ChatModel 断言，确保 BASE 仍然存在）；MCP 可用时 system 文本 = BASE |
| `sse.test.ts`（vitest） | 多字节 UTF-8 被拆在两个 chunk；`\r\n`、`\n`、`\r` 三种换行，以及 `\r` 与 `\n` 被拆开；多行 data；一个 chunk 里有多个事件；注释行；`done`、`error` 事件；abort 不触发 onError；中途断连触发 onError |
| `AssistantStartupIT` | 三个嵌套测试类分别覆盖 `LLM_PROVIDER` 未设置 / `none` / `deepseek` 加空 key：完整应用上下文能启动；`POST /api/chat` 返回 “not configured”；没有 `ChatModel` bean |
| `AssistantStreamSecurityIT` | §6.7 的 5 个用例（真实 Tomcat；`AssistantService` 用 `@MockitoBean` 替换） |
| `AssistantResilienceIT` | 用 `@TestConfiguration` 提供一个可控的 stub `ChatModel`（模式：正常 / 抛异常 / 延迟 35 s）。覆盖：连续 10 次异常后 `/actuator/circuitbreakers` 中 `llm` 为 `OPEN`，并且下一次请求立即（< 500 ms）返回 fallback；1 秒内 25 个并发请求中至少 5 个拿到 fallback（被限流）；超过 30 s 超时时返回 fallback |
| `AssistantToolFlowIT` | stub `ChatModel` 第一次返回调用 `my_orders` 的工具调用，第二次返回文本。断言：调试记录（直接查 `ToolCallRecorder`）中有 `my_orders`，key 为 `userA:conv1`；`OrderService.listForBuyer` 收到的参数是 userA；userB 的会话中没有任何记录 |
| `ShopToolsTest`（mcp-server） | 5 个工具在正常输入、不存在的 id、keyword 非法时的行为（mock 仓库与 Redis） |
| `McpServerIT`（mcp-server） | Testcontainers MySQL：用 `spring.flyway.locations=filesystem:../backend/src/main/resources/db/migration` 以 root 执行迁移，再写入种子数据；Redis 用 Testcontainers。启动 server 后，用 Java `McpClient` 执行 `initialize` 和 `listTools`，断言 5 个工具名，并且 `callTool get_stock` 的结果与种子数据一致 |

## 9. 验收矩阵

工作目录为 `$REPO`；`DC` 数组和 `$P4B_TMP` 见 §7.6。

| 编号 | 验收目标 | 前置/fixture | 工作目录 | 完整命令 | 预期断言 | 证据 | 自动/人工 | 阻塞 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| A1 | 构建 | — | `$REPO` | `./mvnw -B verify` | BUILD SUCCESS（3 个模块） | PR | 自动 | 是 |
| A2 | 单元测试与 IT | Docker | `$REPO` | 已包含在 A1 中 | §8 中的所有类都已执行，并且 0 failures / 0 errors / 0 skipped | reports | 自动 | 是 |
| A3 | 报告检查与前端 | — | `$REPO`；`$REPO/frontend` | `python3 scripts/ci/check_test_reports.py --expect scripts/ci/expected-tests.json`；`npm ci && npm test && npm run build` | 都成功 | PR | 自动 | 是 |
| A4 | 没有 key 时整套服务能启动 | §7.6 | `$REPO` | `"${DC[@]}" up -d --build`；`wait_http http://127.0.0.1:58080/actuator/health 300`；`"${DC[@]}" ps --format json` | 所有服务都是 healthy；`curl -fsS -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"message":"hi"}' 127.0.0.1:58080/api/chat` 的 `reply` 中包含 `not configured` | `scripts/p4b/evidence/a4.txt` | 自动 | 是 |
| A5 | MCP 协议 | A4 | `$REPO` | `python3 scripts/p4b/mcp_probe.py --url http://127.0.0.1:58082/mcp --call get_stock '{"productId":1}'` | 列出的工具恰好是那 5 个；`get_stock` 的结果包含 `totalStock` | `…/a5.json` | 自动 | 是 |
| A6 | backend 能发现工具 | A4（dev profile） | `$REPO` | `curl -fsS -H "Authorization: Bearer $TOKEN" 127.0.0.1:58080/api/assistant/debug/tools` | 工具名集合 = §3 中的 6 个 | `…/a6.json` | 自动 | 是 |
| A7 | MCP 中断与恢复 | A6 | `$REPO` | `"${DC[@]}" stop mcp-server && "${DC[@]}" restart backend`；`wait_http … 300`；查询 `debug/tools`；然后 `"${DC[@]}" start mcp-server`；最多等 90 s，轮询 `debug/tools` | 停止后 backend 仍然 healthy，工具列表只有 `["my_orders"]`，日志中有 `MCP server unavailable`；恢复后 90 s 内工具列表重新变为 6 个 | `…/a7.txt` | 自动 | 是 |
| A8 | 经过 nginx 的流式输出 | 在 env 文件中加 `APP_ASSISTANT_FAKEMODEL=true`（relaxed binding 下，`app.assistant.fake-model` 对应的环境变量名去掉了连字符），然后重启 backend | `$REPO` | `python3 scripts/p4b/sse_timing.py --url http://127.0.0.1:58081/api/assistant/stream --token $TOKEN` | 首个 token 到达的时间 ≤ 500 ms，最后一个 token ≥ 900 ms（假模型共 4 段、每段间隔 300 ms），说明 nginx 没有缓冲 | `…/a8.json` | 自动 | 是 |
| A9 | 只读账号 | A4 | `$REPO` | `"${DC[@]}" exec -T mysql mysql -uftsm_ro -p"$MCP_DB_PASSWORD" ftsm_ecommerce -e "DELETE FROM products WHERE id=-1"`；再用 `MCP_DB_PASSWORD="bad'pass"` 运行一次脚本 | 第一条报 `DELETE command denied`；第二条退出码 1 | `…/a9.txt` | 自动 | 是 |
| A10 | 清理 | — | `$REPO` | `kill $LOGPID; "${DC[@]}" down -v` | 只删除本阶段项目的资源 | — | 自动 | 否 |
| E1 | 真实工具调用 | 人提供 `LLM_PROVIDER=deepseek` 和 key | `$REPO` | 提问“FTSM Hoodie 还有货吗”，然后查看 `debug/tool-calls` | 记录中有 `get_stock`；回答里的数字与数据库一致 | 截图或日志（**不得包含 key**） | 人工 | 否 |
| E2 | 助手评测 | 同 E1 | `$REPO` | `python3 eval/run_assistant_eval.py --base http://127.0.0.1:58080 --token $TOKEN --out eval/results/assistant-<date>.md` | 生成结果文件 | 结果文件 | 人工 | 否 |
| E3 | MCP Inspector 截图 | A4 | — | `npx @modelcontextprotocol/inspector`，连接 `http://127.0.0.1:58082/mcp` | 截图中能看到 5 个工具 | `docs/images/mcp-inspector.png` | 人工 | 否 |


## 10. 升级、重跑与恢复

- 没有数据库迁移。
- 已有的部署环境：
  1. 在 `.env` 中加上 `LLM_PROVIDER=deepseek`（否则助手会被关闭）和 `MCP_DB_PASSWORD`；
  2. 如果数据卷已经存在，按 §6.8 手工创建只读账号。
- 重跑：验收使用独立的 compose 项目；IT 使用一次性容器。
- 恢复：revert 本阶段的 PR；只读账号可以保留，不影响旧版本。
- 清理：只针对 `ftsm-p4b-acc` 项目。

## 11. 完成判定与 PR

- **合并**：A1–A9 全部通过。
- **draft**：任一项失败或未执行。
- **外部待办**（不阻塞合并）：E1–E3，需要真实 key 和截图；在 PR 描述中单列。
- **标题**：`P4b: MCP server, Spring AI assistant with SSE and Resilience4j (v0.10.0)`
- **描述**：沿用 P0 的模板，另加：
  - “Security IT result before change (case 1)”，以及是否修改了 `JwtAuthFilter`；
  - “Upgrade note: set LLM_PROVIDER=deepseek”；
  - “External acceptance pending: E1–E3”。

## 12. 独立 agent prompt

见 [`../agent-prompts/P4b.md`](../agent-prompts/P4b.md)。
