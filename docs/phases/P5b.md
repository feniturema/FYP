# P5b · 向量检索、RRF 融合、LLM 重排、正式跑分

> 版本：v0.12.0 · 前置：P5a；人工完成评测集标注；人工决策 D2（embedding 提供方）· 下一阶段：P6b
> 全局约定见 [`../CHANGE_SPEC.md`](../CHANGE_SPEC.md) §0。证据标记含义同 P0：**[实验]** / **[源码]** / **[未验证]**。

## 1. 目标与非目标

**问题**：关键词检索在同义词、跨语言、带属性约束的查询上召回很差。需要加一路向量召回，用 RRF 融合，再由 LLM 重排，并用标注好的评测集证明效果。

| | 实施前 | 实施后 |
| --- | --- | --- |
| 召回 | 只有 FULLTEXT | FULLTEXT + Redis 向量 KNN（各取 20 条） |
| 融合 | 无 | RRF（k=60），有稳定的 tie-break |
| 重排 | 无 | `LlmReranker`（DeepSeek）；任何失败都回退到 RRF 的顺序 |
| 默认模式 | KEYWORD | `RRF_RERANK`，按 §6.6 的降级链逐级回退 |
| 索引同步 | 无 | 商品和物品发生变化时，提交后异步更新；定时全量对账；支持全量回填 |
| 一致性兜底 | 无 | 检索结果返回前再查一次数据库（被删除的商品、已售出或下架的物品不会返回） |

**非目标**：修改关键词召回；新增数据库迁移（本阶段**没有** SQL 迁移）；前端改动（`effectiveMode` 只在 debug 中显示）。

## 2. 前置条件

| 类别 | 要求 | 检查 | 缺失时 |
| --- | --- | --- | --- |
| 已合并阶段 | P5a | — | 停止 |
| 人工标注 | `main` 上已有 `eval: label queries` 这个提交，且每条查询的 `relevant` 都不为空 | `python3 -c "import json;assert all(json.loads(l)['relevant'] for l in open('eval/queries.jsonl'))"`；`git log --format=%s -- eval/queries.jsonl \| grep -c 'eval: label queries'` ≥ 1 | 停止 |
| **D2** | prompt 中写明 `EMBEDDING_PROVIDER=openai` 或 `ollama` | — | 停止并询问；**不得自行选择** |
| 凭据或运行环境 | `openai`：人以环境变量形式提供 `OPENAI_API_KEY`。`ollama`：能运行 `ollama/ollama:0.35.1`，并且能拉取 `bge-m3`（约 1.2 GB） | — | 可以完成代码和全部离线测试（使用 §8 中的确定性 embedding），但 A8 / A9（正式跑分）标“未执行”，PR 保持 draft |
| 重排需要的 key | `LLM_PROVIDER=deepseek` 加 `LLM_API_KEY`（人提供） | — | A9 中 `rrf_rerank` 这一行标“未执行”；其余三种模式照常跑 |
| 工具 | Docker、JDK 21、Python 3.10 | — | IT 标“未执行”，PR 保持 draft |
| 端口 | `ftsm-p5b-acc` 项目：MySQL 63406、Redis 63479、Kafka 63192、后端 63180、mcp-server 63182（仅 127.0.0.1）、Ollama 61434（见 CHANGE_SPEC §0.11） | — | 停止 |

**D2 决定之前**，agent 可以在本地分支上编写与提供方无关的代码和测试（§5 的 1–7 步），但不得：

- 引入任何 provider 的 starter；
- 用真实模型执行回填或跑分；
- 提交或合并 PR。

## 3. 阶段输入与输出

**输入**：`HybridSearchService`（只有 KEYWORD）、`KeywordRecall`、`SearchController`、`catalog.json` 以及已标注的 `queries.jsonl`、mcp-server 的 `ShopTools`、`ObjectProvider<ChatModel>`（P4b）。

**输出与契约**：

| 输出 | 契约 |
| --- | --- |
| Redis 索引 `idx:catalog:<ver>`，key 前缀 `catalog:<ver>:`，元数据 hash `catalog:vector:meta:<ver>`，内容哈希 hash `catalog:<ver>:hash`，待重试集合 `catalog:<ver>:dirty` | 由 P6b 部署；切换模型就意味着换一个新的 `<ver>` |
| `HybridSearchService` 的 4 种模式，以及 `effectiveMode` 的降级规则 | 前端、mcp-server、评测 |
| `CatalogChangedEvent` | 由 Product、Item、Order 三个 service 发布 |
| `eval/results/retrieval-<date>.md` | 简历第三条的数字来源 |

## 4. 逐文件清单

缩写：
- `C=catalog-core/src/main/java/my/edu/ukm/ftsm/ecommerce`
- `J=backend/src/main/java/my/edu/ukm/ftsm/ecommerce`
- `T=backend/src/test/java/my/edu/ukm/ftsm/ecommerce`
- `M=mcp-server/src/main/java/my/edu/ukm/ftsm/ecommerce/mcp`

| 操作 | 路径 | 职责 | 验证 |
| --- | --- | --- | --- |
| 修改 | `catalog-core/pom.xml` | 新增 `org.springframework.ai:spring-ai-redis-store`（**库模块，不带自动配置**，1.1.8 **[实验：可以解析]**）。**不引入** `spring-ai-starter-vector-store-redis`，以免自动配置再创建一个 VectorStore bean | A1 |
| 修改 | `backend/pom.xml`、`mcp-server/pom.xml` | 只引入 D2 选定的那一个 starter：`spring-ai-starter-model-openai` **或** `spring-ai-starter-model-ollama`（版本由 BOM 管理） | A1 |
| 新增 | `C/search/CatalogVectorStoreHolder.java` | §6.2 | IT |
| 新增 | `C/search/VectorRecall.java` | §6.4 | IT |
| 新增 | `C/search/RrfFusion.java` | §6.5 | 单元测试 |
| 新增 | `C/search/Reranker.java` | 接口：`Optional<List<String>> rerank(String q, List<Candidate> c)`；空表示失败，需要回退 | — |
| 新增 | `C/search/CatalogDocumentMapper.java` | 实体 → `Document`（文本、元数据、内容哈希） | 单元测试 |
| 新增 | `C/search/CatalogIndexer.java` | `upsert(ref)`、`delete(ref)`、`reconcileAll()`、`reindexAll()`（§6.7） | IT |
| 新增 | `C/search/CatalogChangedEvent.java` | `record CatalogChangedEvent(String type, long id)` | — |
| 修改 | `C/search/HybridSearchService.java` | 4 种模式、降级、数据库复查（§6.6） | 单元测试、IT |
| 新增 | `J/search/CatalogIndexListener.java` | `@TransactionalEventListener(phase=AFTER_COMMIT, fallbackExecution=true)`，并用 `@Async("catalogIndexExecutor")` 调用 `indexer.upsert` | IT |
| 新增 | `J/config/CatalogIndexConfig.java` | `catalogIndexExecutor`（单线程，队列 10000，拒绝时把 ref 写入 dirty 集合）；`@Scheduled` 对账（`app.catalog.reconcile-interval`，默认 10 分钟）；`app.catalog.reindex=true` 时执行回填的 `ApplicationRunner` | IT |
| 新增 | `J/search/LlmReranker.java` | `implements Reranker`（§6.8） | `LlmRerankerTest` |
| 修改 | `J/service/ProductService.java`、`J/service/ItemService.java`、`J/service/OrderService.java` | 在 create / update / delete / 售出之后调用 `publisher.publishEvent(new CatalogChangedEvent(…))` | IT |
| 修改 | `J/config/LlmProviderEnvironmentPostProcessor.java` | 新增 embedding 的守卫（§6.1） | 单元测试 |
| 修改 | `J/controller/SearchController.java` | 默认模式改为 `RRF_RERANK`；dev / test 下允许 4 种 `mode` | `SearchControllerTest` |
| 修改 | `M/ShopTools.java` | `search_products` 改为 `HybridSearchService(type=product, mode=RRF)`；`search_secondhand_items` 改为 `(type=item, mode=RRF)` | `ShopToolsTest` |
| 修改 | `backend/src/main/resources/application.yml`、`mcp-server/src/main/resources/application.yml` | §7 | A1 |
| 修改 | `docker-compose.yml` | `EMBEDDING_PROVIDER`、`OPENAI_API_KEY` 或 `OLLAMA_BASE_URL`、`CATALOG_VECTOR_VERSION`、`APP_CATALOG_REINDEX`；选择 `ollama` 时再新增 `ollama/ollama:0.35.1` 服务（数据卷 `ollama_data`） | A6 |
| 新增 | `scripts/p5b/redis_capability.sh` | `redis-cli -h H -p P FT._LIST` 必须成功；然后用一个临时索引执行 `FT.CREATE`（`VECTOR HNSW … DIM 4`）再 `FT.DROPINDEX`，以确认支持向量 | A5 |
| 新增 | `T/search/RrfFusionTest.java`、`T/search/CatalogDocumentMapperTest.java`、`T/search/LlmRerankerTest.java`、`T/search/HybridSearchServiceModesTest.java`、`T/config/EmbeddingGuardTest.java` | §8 | A1 |
| 新增 | `T/it/NoEmbeddingStartupIT.java`、`T/it/VectorSearchIT.java`、`T/it/CatalogIndexSyncIT.java`、`T/it/support/DeterministicEmbeddingModel.java` | §8 | A1 |
| 修改 | `scripts/ci/expected-tests.json` | 加入上面 3 个 IT | A1 |
| 新增 | `eval/results/retrieval-<date>.md` | 正式跑分结果 | A9 |
| 修改 | `README.md`、`HANDOFF.md`、`CHANGELOG.md` | 检索架构；评测表（数据来自结果文件）；v0.12.0 | — |

**禁止修改**：`queries.jsonl` 的 `relevant` 字段（为了提高分数去改评测集是违规的）、`KeywordRecall` 的排序规则、迁移文件。

## 5. 实施顺序

| # | 任务 | 完成条件 | 持久状态 |
| --- | --- | --- | --- |
| 1 | 实现 `RrfFusion` 并编写测试 | 测试通过 | 文件 |
| 2 | 实现 `CatalogDocumentMapper` | 测试通过 | 文件 |
| 3 | 实现 `CatalogVectorStoreHolder`，并用 `DeterministicEmbeddingModel` 写 `VectorSearchIT` | 通过（使用真实的 Redis 8.10.2 容器） | — |
| 4 | 实现 `CatalogIndexer`、listener、executor、对账与回填，写 `CatalogIndexSyncIT` | 通过 | — |
| 5 | 修改 `HybridSearchService`，实现各模式和降级 | 测试通过 | 文件 |
| 6 | 实现 `LlmReranker` 并编写测试（使用 stub ChatModel） | 测试通过 | 文件 |
| 7 | 修改 `NoEmbeddingStartupIT`、`SearchController`、`ShopTools` | 测试通过 | 文件 |
| 8 | **（需要 D2）** 引入 provider 的 starter，修改 EPP 守卫和配置 | A1 通过 | 文件 |
| 9 | compose 验收：检查 Redis 能力、执行回填 | A5–A7 通过 | `ftsm-p5b-acc` 项目 |
| 10 | 正式跑分 | A8、A9 通过 | `eval/results/` |
| 11 | 文档，提 PR | §11 | — |

## 6. 实现契约

### 6.1 装配矩阵与守卫

在 `application.yml` 中**显式**关闭不使用的模型类型。OpenAI starter 同时带有 chat、embedding、image、audio、moderation 的自动配置；其中 `spring.ai.model.chat`、`embedding`、`image`、`moderation`、`audio.speech` 这几个选择属性，已经在 autoconfigure jar 中找到 **[源码]**；`audio.transcription` 的属性名是推断的 **[未验证]**。任何一个没关掉，都可能因为缺少 key 而导致启动失败。`NoEmbeddingStartupIT` 会在 openai starter 存在于 classpath 的情况下证明这一点：

```yaml
spring.ai.model:
  chat: '${LLM_PROVIDER:none}'
  embedding: '${EMBEDDING_PROVIDER:none}'
  image: none
  audio.speech: none
  audio.transcription: none
  moderation: none
```

EPP 中新增 embedding 守卫：

- `EMBEDDING_PROVIDER=openai` 但 `OPENAI_API_KEY` 为空：覆盖为 none，打 WARN；
- `EMBEDDING_PROVIDER` 的值不在 `none`、`openai`、`ollama` 之内：抛出 `IllegalStateException`，启动失败；
- `ollama` 不做 key 检查，连接失败的情况在运行时降级处理。

| 组件 | backend | mcp-server |
| --- | --- | --- |
| `ChatModel` | `LLM_PROVIDER=deepseek` 且有 key 时存在（P4b） | **永远不存在**：固定 `spring.ai.model.chat=none`，并设置 `spring.ai.chat.client.enabled=false` |
| `EmbeddingModel` | `EMBEDDING_PROVIDER` 为 openai（且有 key）或 ollama 时存在 | 同左（使用同一组环境变量） |
| VectorStore | **不是 bean**，由 `CatalogVectorStoreHolder.get()` 在第一次使用时延迟创建，条件是 EmbeddingModel 存在、Redis 能力检查通过、元数据匹配 | 同左 |
| `Reranker` | `LlmReranker`：存在 `ChatModel` 时 `rerank` 才可能返回结果，否则一律返回空 | 不注册 `Reranker` bean，`HybridSearchService` 拿到的是 `Optional.empty()` |
| 默认有效模式 | 有向量且有 chat：`RRF_RERANK`；只有向量：`RRF`；没有向量：`KEYWORD` | 有向量：`RRF`；否则：`KEYWORD` |

没有使用 `@ConditionalOnBean`：所有可选的依赖都通过 `ObjectProvider` 在运行时取得，因此不受 bean 装配顺序的影响。

### 6.2 `CatalogVectorStoreHolder`

```java
@Component
public class CatalogVectorStoreHolder {
    public Optional<VectorStore> get();     // thread-safe lazy init (ReentrantLock), result cached; failures retried after 60 s
    public Status status();                 // DISABLED_NO_EMBEDDING | REDIS_NO_SEARCH | META_MISMATCH | READY | INIT_FAILED
}
```

初始化步骤：

1. `embeddingProvider.getIfAvailable()` 为 null：状态为 `DISABLED_NO_EMBEDDING`。
2. 用 `JedisPooled`（主机、端口、密码取自 `spring.data.redis.*`）执行 `FT._LIST`。抛出 `JedisDataException` 且信息中包含 `unknown command` 时，状态为 `REDIS_NO_SEARCH`，打 ERROR 日志。这就是**运行时的能力检查**，不依赖镜像名称来推断。
3. 读取 `catalog:vector:meta:<ver>`：
   - 不存在：当作新索引；
   - 存在，但 `provider`、`model` 或 `dims` 与当前配置不一致：状态为 `META_MISMATCH`，打 ERROR 日志，提示更换 `CATALOG_VECTOR_VERSION` 并执行回填。
4. 构建 VectorStore：

   ```java
   RedisVectorStore.builder(jedis, embeddingModel)
       .indexName("idx:catalog:" + ver)
       .prefix("catalog:" + ver + ":")
       .metadataFields(tag("type"), tag("category"), tag("status"), numeric("price"), numeric("refId"))
       .initializeSchema(true)
       .build();
   ```

   （API **[实验：编译通过]**）。然后调用 `afterPropertiesSet()`（这个类实现了 `InitializingBean`；它不是 Spring 管理的 bean，所以必须手动调用）。
5. 创建索引成功后，写入元数据 `{provider, model, dims=embeddingModel.dimensions(), createdAt}`。状态为 `READY`。

### 6.3 文档映射（`CatalogDocumentMapper`）

- 文档 id：`product:<id>` 或 `item:<id>`。
- 文本：`title + "\n" + category + "\n" + description`，截断到 2000 个字符。
- 元数据：`{type, refId, category, price（double 类型）, status}`。商品的 status 固定为 `ACTIVE`。
- 内容哈希：对“文本 + 元数据的规范化 JSON”计算 SHA-256，存放在 hash `catalog:<ver>:hash` 中，field 为文档 id。

### 6.4 向量召回（`VectorRecall`）

```java
var b = new FilterExpressionBuilder();
List<Op> ops = new ArrayList<>();
ops.add(b.eq("status", "ACTIVE"));
if (q.type() != ALL) ops.add(b.eq("type", q.type().name().toLowerCase()));
if (q.category() != null) ops.add(b.eq("category", q.category().name()));
if (q.maxPrice() != null) ops.add(b.lte("price", q.maxPrice().doubleValue()));
SearchRequest req = SearchRequest.builder().query(q.q()).topK(20).filterExpression(andAll(b, ops)).build();
```

过滤条件**只能**通过 `FilterExpressionBuilder` 用类型化的值构造（**[实验：编译通过]**），禁止拼接字符串表达式；`category` 已经在入口处校验过是枚举值。

### 6.5 RRF（`RrfFusion`）

- 得分：`score(d) = Σ 1/(60 + rank_i(d))`，`rank` 从 1 开始；某一路没有召回 d 时，这一路的贡献为 0。
- 排序键：`score` 降序 → `min(rank_kw, rank_vec)` 升序（缺失的一路记为 ∞）→ `ref` 字符串升序。整个过程是确定的。
- 输出前 40 个候选，交给数据库复查。

### 6.6 `HybridSearchService` 的模式与降级

```text
requested = (dev/test 下传入的 mode) 或 默认 RRF_RERANK
vector    = holder.get()
effective = requested
  RRF_RERANK 且没有 Reranker 或没有 ChatModel → RRF
  RRF 或 VECTOR 且 vector 为空：
      显式请求（dev/test）→ 400 "vector search not configured"
      默认              → KEYWORD
```

计算步骤：

1. 两路召回各 20 条（KEYWORD 模式只走关键词一路）。
2. 融合。
3. **数据库复查**：按候选顺序批量加载对应实体，丢弃以下候选：
   - 商品已被删除；
   - 物品不存在，或状态不是 `ACTIVE`；
   - 当前价格或类目不再满足请求中的过滤条件。

   取前 10 条（RRF 和 RERANK 模式），或前 `limit` 条。
4. 只有 `RRF_RERANK` 模式执行重排：对前 10 条调用 `reranker.rerank`；结果为空时 `rerankFallback=true`，保持 RRF 的顺序。
5. 截取到 `limit` 条后返回，`effectiveMode` 写入响应。

### 6.7 索引同步

**事件**：在 `ProductService` 的 create、update、delete，`ItemService` 的 create、update、delete（下架），以及 `OrderService.buildItemOrder`（物品售出）中，发布 `CatalogChangedEvent(type, id)`。

- 这些 service 方法当前大多没有 `@Transactional`，所以 listener 必须带上 `fallbackExecution = true`：有事务时在提交之后执行，没有事务时立即执行。

**处理逻辑**（`CatalogIndexer.upsert(ref)`，在单线程的 `catalogIndexExecutor` 中执行）：

1. **从数据库读取当前状态**，不使用事件里携带的数据。这样能自然地处理事件乱序和重复：
   - 实体不存在，或者是非 `ACTIVE` 状态的物品：删除文档、删除哈希、从 dirty 集合中移除；
   - 否则：计算内容哈希；与已存哈希相同时跳过；不同时依次执行 `delete(id)`、`add(doc)`、`HSET` 哈希。
2. embedding 或 Redis 失败时，按 1 s、2 s、4 s 退避重试 3 次；仍然失败就 `SADD dirty ref`，打 WARN。

**删除之后旧的更新又把文档插回来**：因为处理时总是读取数据库的当前状态，一个迟到的旧事件看到的也是“已删除”，所以只会再删一次。

**多副本**：两个副本并发处理同一个 ref 时，可能出现“读到旧状态的副本后写入”的情况。由定时对账兜底，并且检索时还有数据库复查。最终一致的上界是对账周期（10 分钟）。

**`reconcileAll()`**：

1. 先处理 dirty 集合；
2. 按 id 顺序、每页 100 条遍历全部 `ACTIVE` 实体，逐个调用 `upsert`（哈希相同的会被跳过）；
3. 扫描哈希表中有、但数据库里已不存在或已不是 ACTIVE 的 ref，逐个删除。

**`reindexAll()`**（`APP_CATALOG_REINDEX=true` 时在启动后执行一次）：等价于“清空哈希表后执行一次 `reconcileAll()`”。它和实时事件走同一条幂等路径，可以与实时事件并发执行。

### 6.8 `LlmReranker`

- prompt：查询文本，加上 10 行 `ref | title | category | RM price | description 的前 120 个字符`；要求模型只返回 JSON `{"ranking":["ref", …]}`。
- 调用：`ChatClient.builder(chatModel).build().prompt().system(RERANK_SYSTEM).user(...).call().chatResponse()`，超时 8 s；外层加上 `@CircuitBreaker(name="llm")`。
- 结果校验：

  | 输出 | 处理 |
  | --- | --- |
  | 不是合法的 JSON，或缺少 `ranking` | 回退（`Optional.empty()`） |
  | 含有不在候选列表中的 ref | 丢弃这些 ref |
  | 有重复的 ref | 只保留第一次出现的 |
  | 少了某些候选 | 把缺少的候选按原来的 RRF 顺序追加到末尾 |
  | 有效的 ref 少于 3 个 | 回退 |
  | 超时、抛异常、熔断器打开、没有 ChatModel | 回退 |

- 从 `ChatResponse.getMetadata().getUsage()` 读取 token 用量，记录到 `Usage.rerankInputTokens` 和 `rerankOutputTokens`。

## 7. 配置与运行

| 变量 | 默认 | 绑定 | 宿主机 / 容器 |
| --- | --- | --- | --- |
| `EMBEDDING_PROVIDER` | `none` | `spring.ai.model.embedding` | 相同 |
| `OPENAI_API_KEY` | 空 | `spring.ai.openai.api-key` | 相同；**不得**写进任何被提交的文件 |
| `OPENAI_EMBEDDING_MODEL` | `text-embedding-3-small` | `spring.ai.openai.embedding.options.model` | |
| `OLLAMA_BASE_URL` | `http://localhost:11434` | `spring.ai.ollama.base-url` | compose 中为 `http://ollama:11434` |
| `OLLAMA_EMBEDDING_MODEL` | `bge-m3` | `spring.ai.ollama.embedding.options.model` | |
| `CATALOG_VECTOR_VERSION` | `v1` | `app.catalog.vector.version` | |
| `APP_CATALOG_REINDEX` | `false` | `app.catalog.reindex` | |
| `app.catalog.reconcile-interval` | `10m` | — | |

选择 Ollama 时，compose 中 ollama 服务的启动命令：

```bash
docker compose -p ftsm-p5b-acc … up -d ollama
docker compose … exec ollama ollama pull bge-m3
```

`exec` 这一步最长等 600 s。

## 8. 测试清单（离线、确定）

| 类 | 场景 |
| --- | --- |
| `RrfFusionTest` | 两路都有结果；只有一路有结果；分数相同时按 min-rank 排序；min-rank 也相同时按 ref 排序；输入为空 |
| `CatalogDocumentMapperTest` | 文本截断；元数据的类型；内容不变则哈希不变，改动任何一个字段哈希都会变 |
| `LlmRerankerTest` | 用 stub ChatModel 返回：正常的结果；少了 2 项（追加到末尾）；多了未知的 ref（丢弃）；有重复（只保留首次）；非法 JSON（回退）；超时（stub 延迟 10 s，回退）；没有 ChatModel（回退）；有效 ref 只有 2 个（回退） |
| `HybridSearchServiceModesTest` | 降级矩阵：分别 mock 有 / 无向量、有 / 无 ChatModel，覆盖默认请求和显式请求两种情况；数据库复查会过滤掉已售出的物品 |
| `EmbeddingGuardTest` | openai 加空 key → none；值为 `foo` → 抛异常；ollama → 原样保留 |
| `NoEmbeddingStartupIT` | **完整应用**（`AbstractIntegrationTest`）。classpath 上有 D2 选定的 starter；`EMBEDDING_PROVIDER` 不设置、`LLM_PROVIDER` 不设置。断言：上下文能启动；`holder.status()==DISABLED_NO_EMBEDDING`；`GET /api/search?q=hoodie` 返回 200，并且 `effectiveMode=KEYWORD`；`mode=vector`（test profile）返回 400 |
| `VectorSearchIT` | 测试配置提供 `DeterministicEmbeddingModel`（64 维；对文本 token 做哈希，得到确定的向量，相同的词得到相近的向量）；Redis 8.10.2 容器。断言：执行 `FT._LIST` 成功；回填之后能召回；`type`、`category`、`price` 过滤生效；**不同 `<ver>` 但维度不同**的元数据会导致 `META_MISMATCH` |
| `CatalogIndexSyncIT` | 更新商品名称后 5 s 内哈希发生变化；售出物品后文档被删除；先删除、再处理一个迟到的旧 update 事件，文档不会被插回；mock EmbeddingModel 失败 3 次后 ref 进入 dirty 集合，`reconcileAll()` 之后被修复；回填与连续 50 个更新并发执行，结束后执行一次 `reconcileAll()`，哈希表与数据库完全一致 |

## 9. 验收矩阵

| 编号 | 验收目标 | 前置/fixture | 工作目录 | 完整命令 | 预期断言 | 证据 | 自动/人工 | 阻塞 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| A1 | 构建、测试、报告检查 | Docker | `$REPO` | `./mvnw -B verify && python3 scripts/ci/check_test_reports.py --expect scripts/ci/expected-tests.json` | 全部通过 | reports | 自动 | 是 |
| A2 | 评测集未被修改 | — | `$REPO` | `git diff --quiet origin/main...HEAD -- eval/queries.jsonl` | 退出码 0 | PR | 自动 | 是 |
| A3 | 没有拼接过滤表达式 | — | `$REPO` | `grep -rnE 'filterExpression\("' catalog-core backend mcp-server` | 无输出 | PR | 自动 | 是 |
| A4 | 没有自动配置的向量 starter | — | `$REPO` | `grep -rn 'spring-ai-starter-vector-store' */pom.xml` | 无输出 | PR | 自动 | 是 |
| A5 | Redis 的运行时能力 | compose | `$REPO` | `scripts/p5b/redis_capability.sh 127.0.0.1 63479` | 退出码 0 | `scripts/p5b/evidence/a5.txt` | 自动 | 是 |
| A6 | 回填 | D2 的凭据或运行环境 | `$REPO` | 以 `APP_CATALOG_REINDEX=true` 启动；然后执行 `redis-cli -p 63479 FT.INFO idx:catalog:v1` | `num_docs` = 商品数 + ACTIVE 物品数（以 SQL 计数为准） | `…/a6.txt` | 自动（需要 D2 环境） | 是 |
| A7 | 默认模式和 MCP | A6 | `$REPO` | `curl -fsS '127.0.0.1:63180/api/search?q=running%20shoes&debug=true'`；`python3 scripts/p4b/mcp_probe.py --url http://127.0.0.1:63182/mcp --call search_secondhand_items '{"keyword":"kasut"}'` | 前者的 `effectiveMode` 为 `RRF_RERANK`（有 LLM key 时）或 `RRF`（没有时）；后者返回的结果全部是 `item` 类型 | `…/a7.json` | 自动 | 是 |
| A8 | 评测框架烟测 | A6 | `$REPO` | `python3 eval/run_retrieval_eval.py --queries eval/fixtures/queries.smoke.jsonl --modes keyword,vector,rrf --base http://127.0.0.1:63180 --pricing eval/pricing.json --out /tmp/smoke.md` | 退出码 0 | PR | 自动 | 是 |
| A9 | 正式跑分 | 已标注的数据 + D2 + `pricing.json` 已由人填好 | `$REPO` | `python3 eval/run_retrieval_eval.py --modes keyword,vector,rrf,rrf_rerank --base http://127.0.0.1:63180 --pricing eval/pricing.json --out eval/results/retrieval-$(date -u +%F).md` | 退出码 0，失败率 ≤ 5%；结果同时包含总表和按类型的分组表；没有 LLM key 时 `rrf_rerank` 这一行标“未执行” | 结果文件 | 自动 | 是（结果如实报告，不设分数门槛） |
| A10 | README 与结果一致 | A9 | `$REPO` | 人工核对 README 中评测表的数字与结果文件是否一致 | 一致 | PR | 人工 | 是 |

## 10. 升级、重跑与恢复

- 没有 SQL 迁移。
- 切换模型：
  1. 设置 `CATALOG_VECTOR_VERSION=v2` 和新的 provider/model，并设置 `APP_CATALOG_REINDEX=true`，然后启动；
  2. 确认可用后，手工执行 `FT.DROPINDEX idx:catalog:v1 DD` 删除旧索引；
  3. 再执行 `DEL catalog:vector:meta:v1 catalog:v1:hash catalog:v1:dirty`。
- 重跑：回填是幂等的。
- 失败恢复：
  - 把 `EMBEDDING_PROVIDER` 设为 none，检索立即降级为 KEYWORD，不需要回退代码；
  - 删除当前版本的索引和相关 key 后重新回填。
- 清理范围：只针对 `ftsm-p5b-acc` 项目；Ollama 的模型缓存卷属于该项目，`down -v` 时一并删除。

## 11. 完成判定与 PR

- **合并**：A1–A10 全部通过。A9 的分数不设门槛，必须如实报告；如果混合检索没有超过基线，README 照实写。
- **draft**：没有 D2；缺少凭据导致 A6–A9 未执行；任一项失败。
- **外部待办**：无；凭据缺失属于 draft 的条件，而不是外部待办。
- **标题**：`P5b: vector recall, RRF, LLM rerank, retrieval evaluation (v0.12.0)`
- **描述**：沿用 P0 的模板，另加：“D2 = …”、“Eval results (copied from eval/results/…)”、“Modes not executed and why”。

## 12. 独立 agent prompt

见 [`../agent-prompts/P5b.md`](../agent-prompts/P5b.md)。
