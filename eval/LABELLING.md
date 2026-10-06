# eval/queries.jsonl 人工标注说明

依据：`docs/phases/P5a.md` §6.7、`docs/phases/P5b.md` §2（P5b 的前置条件）。标注只由人完成，agent 不填写 `relevant`。

## 1. 当前状态（2026-10-06，main = 87734de）

- 80 行，全部是合法 JSON，`id` 为 q01–q80 且不重复。
- `type`：`lexical`、`synonym`、`crosslingual`、`constraint` 各 20 条。
- 20 条 `constraint` 都有 `filters`，其余 60 条没有。filter 取值都合法（类目属于 10 个 `CatalogCategory`，`type` 属于 product/item/all，`maxPrice` 在 0–1000000 之间）。
- 80 条的 `relevant` 都是空列表。

## 2. 字段含义

| 字段 | 含义 | 标注时是否修改 |
| --- | --- | --- |
| `id` | 查询编号 q01–q80 | 不改 |
| `q` | 用户输入的搜索词，评测时原样发给 `GET /api/search` | 不改 |
| `type` | 查询类别：`lexical` 字面匹配；`synonym` 同义词或近义说法；`crosslingual` 查询和目标商品的语言不同（en / ms / zh）；`constraint` 带价格、类目或类型约束 | 不改 |
| `filters` | 只有 `constraint` 类有。`maxPrice`：价格上限（含）；`category`：类目；`type`：`product` 只要商品，`item` 只要二手物品，`all` 两者都要 | 不改 |
| `relevant` | 正确答案。1–3 个引用，格式为 `product:<id>` 或 `item:<id>` | **只填这一项** |

id 范围（`backend/src/main/resources/demo/catalog.json`）：商品 1001–1400（字段 `name`），二手物品 5001–5200（字段 `title`）。

## 3. 标注规则

1. **以目录内容为准，不以搜索结果为准。** 候选要从 `catalog.json` 中找，按 `name`/`title`、`description`、`category` 判断，并检查三种语言的条目。不要只从 `/api/search` 的返回里挑：那样标出来的答案会偏向关键词检索，P5b 的对比就失去意义。
2. **相关 = 用户会认为这正是他要找的东西。** 只是共享几个字母不算（例如 `hoodie` 和 "Maroon Instant Noodles"）。
3. **每条 1–3 个。** 如果符合条件的超过 3 个，选和查询意图最直接匹配的 3 个。指标按集合计算，顺序不影响结果。
4. **`crosslingual`**：答案应该是另一种语言的条目，这正是这一类要测的能力。同一个东西有同语言的条目时，不要只标同语言的那个。
5. **`constraint`**：每个答案都必须满足全部 filter：价格 ≤ `maxPrice`、类目相同、`type` 为 product 时只标 `product:`，为 item 时只标 `item:`。下面的检查脚本会自动核对。
6. **找不到合理答案时，不要硬凑。** 记下这条 id，改写 `q` 或换一条查询。改动要在提交说明中逐条写明，并保持四类各 20 条。
7. 标注完成并合并后，评测集就冻结。P5b 规定不得为了提高分数修改 `relevant`。

## 4. 检查与提交

在仓库根目录、基于最新 `main` 的分支上：

```bash
git switch -c eval-labels origin/main
python3 eval/check_labels.py --checklist > /tmp/labelling-checklist.md   # 80 条待办清单（不含答案）
# 编辑 eval/queries.jsonl：只填写 relevant
python3 eval/check_labels.py --base origin/main
python3 -c "import json;assert all(json.loads(l)['relevant'] for l in open('eval/queries.jsonl'))"
git diff --stat
git add eval/queries.jsonl
git commit -m "eval: label queries"
```

- `eval/check_labels.py --base origin/main` 检查以下几点，全部通过时输出 `OK: 80 queries, N refs`，退出码 0：
  - 80 条、四类各 20 条、id 不重复；
  - 每条 1–3 个引用，且没有重复；
  - 每个引用都存在于 `catalog.json`；
  - `constraint` 类的答案满足 filter；
  - 与 `origin/main` 相比，只有 `relevant` 发生了变化。
- 第二条 python 命令是 P5b 前置条件里的原文检查。
- `git diff --stat` 只应显示 `eval/queries.jsonl` 一个文件。
- 提交信息的标题必须**完全等于** `eval: label queries`。P5b 用 `git log --format=%s -- eval/queries.jsonl | grep -c 'eval: label queries'` 确认这个提交。
- 推送后开 PR，用 merge commit 合并（项目之前的 PR 都这样合并）。如果用 squash 合并，PR 标题也必须是 `eval: label queries`。

### P5b 启动门槛（全部满足才开始）

1. 文档端口修正 PR #11 已合并（`87734de`，已满足）。
2. 80 条查询已由人工标注，标注提交的标题为 `eval: label queries`，并已合并到 `main`。
3. `python3 eval/check_labels.py` 在 `main` 上退出码为 0。
4. D2（embedding 提供方）已明确记录。
5. 工作树干净。

## 5. 不要做的事

- 不改 `id`、`q`、`type`、`filters`。第 3 节第 6 条的情况除外，且需要在提交说明中写明。
- 不把 `eval/fixtures/queries.smoke.jsonl` 里由 agent 标注的答案复制过来。
- 不让 agent 代填 `relevant`。
- 不在同一个提交里混入其他改动。
