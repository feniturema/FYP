# P5a · 演示目录、FULLTEXT 检索、`/api/search`、评测框架

> 版本：v0.11.0 · 前置：P4b（以及 P6a 提供的 IT 基础设施）· 下一阶段：人工标注 → P5b
> 全局约定见 [`../CHANGE_SPEC.md`](../CHANGE_SPEC.md) §0。证据标记含义同 P0：**[实验]** / **[源码]** / **[未验证]**。

## 1. 目标与非目标

**问题**：

- 目录里只有 3 个种子商品，没法评测检索效果；
- 检索只有 `LIKE %kw%`，并且只匹配商品名；
- 没有统一的检索接口，也没有评测工具。

| | 实施前 | 实施后 |
| --- | --- | --- |
| 演示数据 | 3 个商品 | `backend/src/main/resources/demo/catalog.json`：400 个商品、200 个物品；只能在 `demo` profile 下，导入到明确指定的数据库 |
| 关键词检索 | `LIKE` | MySQL FULLTEXT（ngram parser），覆盖商品和物品，带过滤条件 |
| 接口 | `/api/products?q=`、`/api/items?q=` | 新增 `GET /api/search`（公开）；原有接口保持不变 |
| 类目 | 自由文本 | `CatalogCategory` 枚举：10 个类目，作为检索参数的校验标准，P7 也会用到 |
| 前端 | 输入框直接请求 | 防抖、取消旧请求、避免竞态，并有 loading / empty / error 三种状态 |
| 评测 | 无 | `eval/queries.jsonl`（80 条，`relevant` 为空，等待人工标注）、`eval/run_retrieval_eval.py`、`eval/pricing.json` |

**非目标**：向量检索、RRF、重排（P5b）；修改 mcp-server 工具的签名（工具内部改为调用新的 `CatalogSearchService`，签名不变）；标注评测集（人工完成）。

## 2. 前置条件

| 类别 | 要求 | 缺失时 |
| --- | --- | --- |
| 已合并阶段 | P4b（`CatalogSearchService` 的签名、mcp-server）、P6a（`AbstractIntegrationTest`） | 停止 |
| 迁移 | V1、V2（以及可能存在的 V1_1）已存在，本阶段新增 V3 | — |
| 工具 | Docker、JDK 21、Node 20、Python 3.10 | 没有 Docker：IT 和 compose 验收标“未执行”，PR 保持 draft |
| 端口 | `ftsm-p5a-acc` 项目：MySQL 63306、Redis 66379、Kafka 69092、后端 68080、前端 68081 | 停止 |
| 凭据、人工决策 | 都不需要。标注工作在本阶段之后进行 | — |

## 3. 阶段输入与输出

**输入**：`CatalogSearchService`（P4b，基于 LIKE）、`SearchDtos`、`Marketplace.tsx`、`ProductController`、`ItemController`。

**输出与契约**：

| 输出 | 契约 |
| --- | --- |
| `V3__catalog_fulltext.sql` | 下一个迁移编号是 V4（P6b） |
| `CatalogCategory` 枚举（catalog-core） | P7 的 `category` 校验也用它 |
| `HybridSearchService.search(SearchQuery): SearchResult`，`enum Mode {KEYWORD, VECTOR, RRF, RRF_RERANK}` | P5b 实现另外 3 种模式；本阶段只有 `KEYWORD` 可用 |
| `GET /api/search`（§6.4） | 前端、评测脚本、P5b |
| `catalog.json` 中固定的 id（商品 1001–1400，物品 5001–5200） | 评测集通过这些 id 引用 |
| `eval/queries.jsonl` 的格式（§6.7） | 人工标注；P5b |

## 4. 逐文件清单

缩写：
- `C=catalog-core/src/main/java/my/edu/ukm/ftsm/ecommerce`
- `J=backend/src/main/java/my/edu/ukm/ftsm/ecommerce`
- `T=backend/src/test/java/my/edu/ukm/ftsm/ecommerce`
- `F=frontend/src`

| 操作 | 路径 | 职责 / 主要符号 | 验证 |
| --- | --- | --- | --- |
| 新增 | `backend/src/main/resources/db/migration/V3__catalog_fulltext.sql` | §6.1 | A2 |
| 新增 | `C/search/CatalogCategory.java` | 10 个枚举值，以及 `static Optional<CatalogCategory> parse(String)`（不区分大小写） | 单元测试 |
| 新增 | `C/search/SearchQuery.java` | record（§6.4） | 单元测试 |
| 新增 | `C/search/SearchResult.java` | `SearchResult`、`SearchHit`、`SearchDebug`、`Usage` 四个 record | — |
| 新增 | `C/search/KeywordRecall.java` | 原生 SQL 召回（§6.3） | `CatalogSearchIT` |
| 新增 | `C/search/HybridSearchService.java` | `search(SearchQuery)`；只实现 `KEYWORD` 模式 | 单元测试与 IT |
| 修改 | `C/search/CatalogSearchService.java` | 签名不变，内部改为调用 `KeywordRecall`（只取商品或只取物品）；排序规则与 §6.3 相同 | `ShopToolsTest`（mcp-server，已存在） |
| 新增 | `J/controller/SearchController.java` | `GET /api/search` | `SearchControllerTest`、IT |
| 修改 | `J/config/SecurityConfig.java` | `GET /api/search` 允许匿名访问 | `SearchControllerTest` |
| 新增 | `backend/src/main/resources/demo/catalog.json` | 由 `scripts/p5a/gen_catalog.py` 生成并提交 | A5 |
| 新增 | `scripts/p5a/gen_catalog.py` | 确定性生成（`random.Random(42)`，模板加词表，英语/马来语/中文 = 6:2:2，并且刻意加入同义词） | A5 |
| 新增 | `J/demo/DemoCatalogSeeder.java` | `@Profile("demo")` 的 `ApplicationRunner`：调用 importer，再调用 `adjustAutoIncrement` | A6 |
| 新增 | `J/demo/DemoCatalogImporter.java` | `@Transactional public ImportReport importCatalog(Catalog)`（§6.2） | `DemoCatalogImportIT` |
| 新增 | `J/demo/CatalogJson.java` | Jackson 映射用的 record，以及 `validate()` | `CatalogJsonValidationTest` |
| 新增 | `T/demo/CatalogJsonValidationTest.java` | 真实的 `catalog.json` 能通过校验；3 份故意写错的样例（`T/../resources/demo/bad-*.json`）分别被拒 | A1 |
| 新增 | `backend/src/test/resources/demo/bad-duplicate-id.json`、`bad-category.json`、`bad-seller.json` | fixture | A1 |
| 新增 | `T/search/HybridSearchServiceTest.java`、`T/controller/SearchControllerTest.java` | 参数校验与排序 | A1 |
| 新增 | `T/it/CatalogSearchIT.java`、`T/it/DemoCatalogImportIT.java` | §8 | A1 |
| 修改 | `scripts/ci/expected-tests.json` | 加入上面 2 个 IT | A1 |
| 修改 | `docker-compose.yml` | backend 增加环境变量 `APP_DEMO_TARGET_DB: ${APP_DEMO_TARGET_DB:-}`、`SEED_DEMO_PASSWORD: ${SEED_DEMO_PASSWORD:-}`、`APP_DEMO_ON_CONFLICT: ${APP_DEMO_ON_CONFLICT:-fail}` | A6 |
| 新增 | `F/features/marketplace/searchController.ts`、`F/features/marketplace/searchController.test.ts` | §6.6 | A3 |
| 新增 | `F/features/marketplace/SearchResults.tsx` | 结果列表 | A3 |
| 修改 | `F/features/marketplace/Marketplace.tsx`、`F/services/api.ts`、`F/types/index.ts` | 接入 `searchApi.search(params, signal)` | A3 |
| 新增 | `eval/queries.jsonl`、`eval/README.md`、`eval/pricing.json`、`eval/run_retrieval_eval.py`、`eval/fixtures/queries.smoke.jsonl` | §6.7 | A7 |
| 修改 | `README.md`、`HANDOFF.md`、`CHANGELOG.md` | v0.11.0；demo profile 的说明 | — |

**禁止修改**：已有的 `/api/products`、`/api/items` 接口的行为；mcp-server 工具的签名；V1、V2 迁移。

## 5. 实施顺序

| # | 任务 | 完成条件 | 持久状态 |
| --- | --- | --- | --- |
| 1 | V3 与 `CatalogSearchIT` 的骨架 | IT 中的 Flyway 执行到版本 3 | — |
| 2 | `CatalogCategory`、`SearchQuery`、`KeywordRecall` | `CatalogSearchIT` 通过 | — |
| 3 | `HybridSearchService`（KEYWORD）、`SearchController`、`SecurityConfig` | 单元测试通过 | 文件 |
| 4 | `CatalogSearchService` 改为调用新实现 | mcp-server 的测试通过 | 文件 |
| 5 | `gen_catalog.py` → `catalog.json` | A5 通过 | 文件 |
| 6 | `CatalogJson` 校验、importer、seeder | `DemoCatalogImportIT` 和 `CatalogJsonValidationTest` 通过 | — |
| 7 | 前端 | A3 通过 | 文件 |
| 8 | 评测框架、`queries.jsonl` 草稿（`relevant` 为空）、smoke fixture | A7 通过 | 文件 |
| 9 | compose 验收 | A4、A6 通过 | `ftsm-p5a-acc` 项目 |
| 10 | 文档，提 PR | §11 | — |

## 6. 实现契约

### 6.1 `V3__catalog_fulltext.sql`

```sql
ALTER TABLE products ADD FULLTEXT INDEX ft_products (name, description, category) WITH PARSER ngram;
ALTER TABLE items    ADD FULLTEXT INDEX ft_items (title, description, category) WITH PARSER ngram;
```

`ngram_token_size` 使用 MySQL 默认值 2，所以查询词少于 2 个字符时不会命中；这是预期的语义，写进 README。

### 6.2 演示数据的导入

**文件进入 jar 和容器的方式**：

- `catalog.json` 放在 `backend/src/main/resources/demo/` 下，打包后成为 classpath 资源 `demo/catalog.json`；Docker 镜像里的 jar 自然包含它。
- 运行时通过 `new ClassPathResource("demo/catalog.json")` 读取。
- 评测脚本直接读取仓库里的同一个文件。

**只能写入指定的演示环境**：

1. 只有在 `demo` profile 下，`DemoCatalogSeeder` 才会被注册。
2. 必须设置 `APP_DEMO_TARGET_DB`（对应属性 `app.demo.target-db`），并且它要与 `SELECT DATABASE()` 的结果**完全一致**，否则启动失败。
3. 如果目标库是 `ftsm_ecommerce`（compose 的默认库），还必须额外设置 `APP_DEMO_ALLOW_DEFAULT_DB=true`。
4. `SEED_DEMO_PASSWORD` 不能为空，长度 ≥ 12。

**原子导入**：

- `DemoCatalogSeeder`（一个独立的 bean）调用 `importer.importCatalog(catalog)`。importer 是另一个 bean，通过 Spring 代理调用，事务才会生效；**不得**在同一个类里自调用。
- `importCatalog` 在**一个**事务里完成以下全部步骤：
  1. 创建或复用 20 个卖家：`sellerNN@siswa.ukm.edu.my`，NN 为 01–20，`role=STUDENT`、`emailVerified=true`，密码用 `PasswordEncoder` 加密。已存在就复用。
  2. 建立映射 `Map<String sellerKey, Long sellerId>`，id 一律从数据库读出，**不假设**卖家 id 是连续的。
  3. 逐行处理商品和物品：先按 id 用 `SELECT … FOR UPDATE` 查询。
     - **不存在**：普通的 `INSERT`（显式写入 id）。任何约束错误都会抛出异常，导致整个事务回滚。**不使用** `INSERT IGNORE`。
     - **存在且内容相同**：跳过，记为 `unchanged`。“内容相同”指所有业务字段的规范化 JSON 相等。
     - **存在且内容不同**：
       - `app.demo.on-conflict=fail`（默认）：抛出 `DemoImportConflictException`，信息中列出全部冲突的 id，整个事务回滚；
       - `skip`：不修改这一行，记入 `report.conflicts`（id 加字段差异），打 WARN 日志，并把报告以 JSON 形式写到 `${java.io.tmpdir}/demo-import-report.json`，作为可审计的跳过记录。
- 事务提交之后，`DemoCatalogSeeder` 再调用 `adjustAutoIncrement()`：执行 `ALTER TABLE products AUTO_INCREMENT = 10000` 和 `ALTER TABLE items AUTO_INCREMENT = 10000`。
  - MySQL 的 `ALTER TABLE` 会**隐式提交**，所以这一步**不在**原子导入之内。
  - 这一步是幂等的：MySQL 会取 max(当前值, 10000)。
  - 它单独失败只会打 WARN，不会撤销已经导入的数据。
- `CatalogJson.validate()` 在任何数据库操作之前执行，内容包括：
  - id 唯一，且商品在 1001–4999、物品在 5001–9999 之间；
  - `category` 属于 `CatalogCategory`；
  - `price > 0` 且 ≤ 100000，最多 2 位小数；
  - `condition` 属于 `NEW`、`LIKE_NEW`、`USED`；
  - `sellerKey` 匹配 `^seller(0[1-9]|1[0-9]|20)$`；
  - 名称和标题为 1–200 个字符，描述 ≤ 2000 个字符；
  - `lang` 属于 `en`、`ms`、`zh`。
- 并发：只支持单个实例运行导入。如果两个实例同时导入，后一个会在 INSERT 时撞上主键冲突，事务回滚，应用启动失败；之后单实例重跑是幂等的。

### 6.3 关键词召回（`KeywordRecall`）

商品（`ProductRow(id, score)`）：

```sql
SELECT id, MATCH(name, description, category) AGAINST (:q IN NATURAL LANGUAGE MODE) AS score
FROM products
WHERE MATCH(name, description, category) AGAINST (:q IN NATURAL LANGUAGE MODE)
  AND (:maxPrice IS NULL OR price <= :maxPrice)
  AND (:category IS NULL OR category = :category)
ORDER BY score DESC, id ASC
LIMIT 20
```

物品与之相同，额外加上 `AND status = 'ACTIVE'`。

两路合并：

1. 每一路的分数分别除以该路的最高分，得到归一化分数 ∈ (0, 1]。两张表的 FULLTEXT 分数基于不同的统计量，不能直接比较。
2. 排序键依次为：归一化分数降序 → 原始分数降序 → `type`（product 排在 item 前面）→ `id` 升序。
3. 取前 `limit` 条。`SearchHit.score` 存放归一化分数。

### 6.4 `GET /api/search`

**请求参数**：

| 参数 | 类型 | 规则 | 不合法时 |
| --- | --- | --- | --- |
| `q` | string | 必填；trim 后长度为 1–200 | 400 `q must be 1-200 characters` |
| `maxPrice` | decimal | 可选；0 ≤ x ≤ 1000000；最多 2 位小数 | 400 |
| `category` | string | 可选；必须是 `CatalogCategory` 的某个名称（不区分大小写） | 400，提示中列出允许的值 |
| `type` | `product`/`item`/`all` | 默认 `all` | 400 |
| `limit` | int | 1–20，默认 10 | 400 |
| `mode` | `keyword`/`vector`/`rrf`/`rrf_rerank` | **只在 `dev`、`test` profile 下生效**，其他 profile 忽略这个参数；P5a 只允许 `keyword`，其他值返回 400 `mode not available` | 400 |
| `debug` | boolean | 只在 `dev`、`test` 下生效 | — |

**响应**：

```json
{"query":"…","effectiveMode":"KEYWORD",
 "hits":[{"ref":"product:1001","type":"product","id":1001,"title":"…","price":79.00,"category":"Apparel","imageUrl":"…","score":1.0}],
 "debug":{"ranks":{"product:1001":{"keyword":1}},"usage":{"embeddingTokens":0,"rerankInputTokens":0,"rerankOutputTokens":0,"rerankFallback":false},"latencyMs":12}}
```

- 不传 `debug` 时，响应里没有 `debug` 字段。
- 没有结果时返回 200 和空的 `hits`。
- 匿名可访问。

`SearchQuery` record：

```java
record SearchQuery(String q, BigDecimal maxPrice, CatalogCategory category, Type type, int limit, Mode mode, boolean debug)
```

### 6.5 MCP 工具

- `search_products` / `search_secondhand_items` 内部改为调用 `KeywordRecall`：分别只查商品或只查物品，排序规则与 §6.3 相同。
- 工具签名不变。工具的 `category` 参数取值不合法时，按“不过滤”处理并在结果中加入提示，而不是抛异常。

### 6.6 前端检索（`searchController.ts`）

```ts
export type SearchState = { status: 'idle'|'loading'|'results'|'empty'|'error'; hits: SearchHit[]; error?: string };
export function createSearchController(fetcher: (p: SearchParams, s: AbortSignal) => Promise<SearchResponse>,
                                       onState: (s: SearchState) => void, debounceMs = 300): { setQuery(q: string, filters?: Filters): void; dispose(): void }
```

规则：

- 输入停止 300 ms 后才发请求。
- 发新请求时 `abort()` 上一个请求。
- 每个请求都带递增的序号，只采用最新序号的响应，以此防止竞态。
- trim 后为空时，状态回到 `idle`，不发请求。
- 被 `abort()` 的请求不进入 `error` 状态。
- HTTP 400 或网络错误时进入 `error` 状态，显示服务端返回的 `message`。
- `Marketplace.tsx`：输入框为空时保持原有的浏览列表；有关键词时显示 `SearchResults`。

### 6.7 评测

`eval/queries.jsonl` 的每一行：

```json
{"id":"q01","q":"200 令吉以内的蓝牙耳机","type":"constraint","filters":{"maxPrice":200,"category":"Electronics","type":"product"},"relevant":[]}
```

- 四类查询各 20 条：`lexical`、`synonym`、`crosslingual`、`constraint`。
- `constraint` 类的 `filters` 不得为空。
- `relevant` 由人填写，取值形如 `["product:1001", …]`，每条 1–3 个。

`eval/pricing.json`（由人在跑分当天填写）：

```json
{"currency":"USD","date":"YYYY-MM-DD","source":"<pricing page url>",
 "embedding_per_1m_tokens":0.0,"chat_input_per_1m_tokens":0.0,"chat_output_per_1m_tokens":0.0}
```

`eval/run_retrieval_eval.py`：

- 参数：`--base`、`--queries`（默认 `eval/queries.jsonl`）、`--modes`、`--pricing`、`--out`、`--timeout 10`、`--limit 10`。
- 每条查询都会发送 `q`、`limit`、`mode`、`debug=true`，以及 `filters` 中的**所有**键（`maxPrice`、`category`、`type`）。
- 遇到 `relevant` 为空的查询，退出码 3，并列出它们的 id。
- 指标：
  - `Recall@5` = 对每条查询计算 |relevant ∩ top5| / |relevant|，再对全部查询取平均；
  - `MRR@10` = 第一个相关结果名次的倒数（前 10 名里没有则记 0），对全部查询取平均；
  - `p50 latency`：只统计成功的请求；
  - `cost per 1000 queries` = (embeddingTokens × embedding 单价 + rerankIn × 输入单价 + rerankOut × 输出单价) / 1e6 / 成功查询数 × 1000。
- 请求失败（非 200 或超时）时，这条查询的 Recall 和 MRR 都记为 0，并计入 `failures`。失败率超过 5% 时退出码 2。
- 输出：总表，以及按 `type` 分组的表。

`eval/fixtures/queries.smoke.jsonl`：5 条由 agent 标注的查询，**只用于**测试评测框架本身，不得用于报告结果；文件头的注释和 README 都要写明这一点。

## 7. 配置与运行

| 变量 | 默认 | 绑定 | 说明 |
| --- | --- | --- | --- |
| `APP_DEMO_TARGET_DB` | 空 | `app.demo.target-db` | 在 demo profile 下必填 |
| `APP_DEMO_ALLOW_DEFAULT_DB` | `false` | `app.demo.allow-default-db` | |
| `APP_DEMO_ON_CONFLICT` | `fail` | `app.demo.on-conflict` | `fail` / `skip` |
| `SEED_DEMO_PASSWORD` | 空 | `app.demo.seller-password` | 在 demo profile 下必填 |

compose 中的 backend 对应地新增这些 `environment` 条目（compose 不会把 env 文件里的变量自动传进容器）。

验收环境：

- 项目名 `ftsm-p5a-acc`，`DB_NAME=ftsm_demo`；
- `SPRING_PROFILES_ACTIVE=dev,demo`；
- `APP_DEMO_TARGET_DB=ftsm_demo`；
- `SEED_DEMO_PASSWORD=$(openssl rand -hex 12)`；
- 显式使用 `--env-file`。

## 8. 测试清单

| 类 | 场景 |
| --- | --- |
| `CatalogJsonValidationTest` | 真实文件通过；重复 id、未知类目、未知 sellerKey 三种错误样例都被拒，异常信息中包含对应的 id |
| `HybridSearchServiceTest` | 归一化与合并；四级 tie-break（构造同分数据）；`type=item` 只返回物品；`limit` 生效 |
| `SearchControllerTest`（`@WebMvcTest`，加上安全配置） | 每个参数的边界：`q` 为空、201 个字符、只有空白；`maxPrice` 为 -1、3 位小数、1000000.01；`category` 未知；`limit` 为 0 和 21；非 dev profile 下传 `mode=vector` 被忽略；dev 下返回 400；匿名访问返回 200 |
| `CatalogSearchIT` | 真实 MySQL 加 V3：写入英语、马来语、中文商品各 3 个，再加 1 个 SOLD 状态的物品。断言：`hoodie` 命中英语商品；`kasut` 命中马来语商品；`耳机` 命中中文商品；单个汉字没有结果；`maxPrice` 和 `category` 过滤生效；SOLD 物品不出现；两次查询的结果顺序完全相同 |
| `DemoCatalogImportIT` | 首次导入：商品 400、物品 200、卖家 20，自增值 ≥ 10000；第二次导入：0 插入、600 unchanged；修改一行后再导入：`fail` 模式下抛出异常，并且**整个事务回滚**（为验证这一点，先用 SQL 删除另一行，在该次失败的导入之后它仍然不存在）；`skip` 模式下生成报告；`APP_DEMO_TARGET_DB` 不匹配 → 启动失败；`SEED_DEMO_PASSWORD` 为空 → 启动失败 |
| `searchController.test.ts` | 防抖（fake timers）；新输入会 abort 旧请求；旧响应晚到时被忽略；空输入回到 idle；400 进入 error；abort 不进入 error |
| 评测框架 | `run_retrieval_eval.py --queries eval/fixtures/queries.smoke.jsonl --modes keyword`：退出码 0；用 `eval/queries.jsonl` 运行时退出码 3（因为还没有标注） |

## 9. 验收矩阵

| 编号 | 验收目标 | 前置/fixture | 工作目录 | 完整命令 | 预期断言 | 证据 | 自动/人工 | 阻塞 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| A1 | 构建、单元测试、IT、报告检查 | Docker | `$REPO` | `./mvnw -B verify && python3 scripts/ci/check_test_reports.py --expect scripts/ci/expected-tests.json` | 全部通过 | reports | 自动 | 是 |
| A2 | V3 执行 | A1 中的 IT | `$REPO` | `MigrationIT` 中的期望版本列表改为 `[1,2,3]`（以及可能存在的 1.1） | 通过 | reports | 自动 | 是 |
| A3 | 前端 | — | `$REPO/frontend` | `npm ci && npm test && npm run build` | 全部通过 | PR | 自动 | 是 |
| A4 | API 冒烟 | compose（§7） | `$REPO` | 依次执行：`curl -fsS '127.0.0.1:68080/api/search?q=hoodie'`；`curl -s -o /dev/null -w '%{http_code}' '127.0.0.1:68080/api/search?q='`；`curl -fsS '127.0.0.1:68080/api/search?q=%E8%80%B3%E6%9C%BA&limit=5'` | 第一条返回 200 且 `hits` 非空；第二条返回 400；第三条返回 200，且至少有 1 条 `lang=zh` 的结果（对照 catalog.json 中的 id） | `scripts/p5a/evidence/a4.txt` | 自动 | 是 |
| A5 | 数据可以复现 | — | `$REPO` | `python3 scripts/p5a/gen_catalog.py --out /tmp/c.json && diff -q /tmp/c.json backend/src/main/resources/demo/catalog.json` | 两者完全相同；共 400 个商品、200 个物品；每个类目至少有 20 个商品 | PR | 自动 | 是 |
| A6 | demo 导入 | compose | `$REPO` | 启动 compose；`"${DC[@]}" exec -T mysql mysql … -e "SELECT COUNT(*) FROM products WHERE id BETWEEN 1001 AND 4999"`；然后 `"${DC[@]}" restart backend`，再查一次 | 两次都是 400；第二次启动的日志中有 `inserted 0`、`unchanged 600` | `…/a6.txt` | 自动 | 是 |
| A7 | 评测框架 | A6 | `$REPO` | `python3 eval/run_retrieval_eval.py --base http://127.0.0.1:68080 --queries eval/fixtures/queries.smoke.jsonl --modes keyword --pricing eval/pricing.json --out /tmp/eval.md`；再用 `--queries eval/queries.jsonl` 运行一次 | 第一次退出码 0，输出中有 Recall@5 和 MRR 两列；第二次退出码 3 | PR | 自动 | 是 |
| A8 | 评测集草稿的格式 | — | `$REPO` | `python3 - <<'PY'`（校验 80 行、四类各 20 行、constraint 类的 filters 非空、`relevant == []`）`PY` | 通过 | PR | 自动 | 是 |
| A9 | 清理 | — | `$REPO` | `"${DC[@]}" down -v` | — | — | 自动 | 否 |

## 10. 升级、重跑与恢复

- V3 只新增索引，对已有数据只读。在大表上会锁表并重建索引；本项目的数据量下耗时可以忽略，在 README 里说明即可。
- demo 导入是幂等的（§6.2），并且只能写入 `APP_DEMO_TARGET_DB` 指定的库。
- 恢复：
  - 撤销 V3：`ALTER TABLE products DROP INDEX ft_products; ALTER TABLE items DROP INDEX ft_items;`，然后删除 `flyway_schema_history` 中版本 3 那一行。只能手工操作，不支持自动降级。
  - 删除演示数据：

    ```sql
    DELETE FROM items WHERE id BETWEEN 5001 AND 9999;
    DELETE FROM products WHERE id BETWEEN 1001 AND 4999;
    DELETE FROM users WHERE email REGEXP '^seller(0[1-9]|1[0-9]|20)@siswa\\.ukm\\.edu\\.my$';
    ```

    把这几条 SQL 写进 README。
- 清理范围：只针对 `ftsm-p5a-acc` 项目。

## 11. 完成判定与 PR

- **合并**：A1–A8 全部通过。
- **draft**：任一项失败或未执行。
- **合并后的人工步骤**（不阻塞本 PR，但**阻塞 P5b**）：标注 `eval/queries.jsonl`，单独提交，commit 信息为 `eval: label queries`。
- **标题**：`P5a: FULLTEXT search API, demo catalogue, retrieval eval harness (v0.11.0)`
- **描述**：沿用 P0 的模板，另加 “Eval set status: unlabeled (80 queries)”。

## 12. 独立 agent prompt

见 [`../agent-prompts/P5a.md`](../agent-prompts/P5a.md)。
