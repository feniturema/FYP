# Evaluation

## Retrieval (P5a)

| File | What it is |
| --- | --- |
| `queries.jsonl` | 80 draft queries, 20 each of `lexical`, `synonym`, `crosslingual`, `constraint`. **`relevant` is empty: a person labels it after P5a is merged** (1–3 refs each, e.g. `["product:1001"]`, ids from `backend/src/main/resources/demo/catalog.json`), committed separately as `eval: label queries`. P5b is blocked until then. |
| `fixtures/queries.smoke.jsonl` | 5 queries labelled by the agent, **only** to test `run_retrieval_eval.py` itself. Never report results from it. |
| `pricing.json` | Prices per 1M tokens, filled in by a person on the day of a run (date and source page). |
| `LABELLING.md` | How a person labels `queries.jsonl`: field meanings, labelling rules, the checks and the exact commit message, plus the P5b start gates (in Chinese). |
| `check_labels.py` | Checks the labels against `catalog.json` (1–3 existing refs per query, constraint filters respected, with `--base origin/main` only `relevant` changed); `--checklist` prints an empty Markdown checklist. Never writes labels. |
| `run_retrieval_eval.py` | Runs every query against `GET /api/search` and reports Recall@5, MRR@10, p50 latency and cost per 1000 queries, overall and per type. |

Line format of `queries.jsonl` (`filters` is required for `constraint` and may hold `maxPrice`, `category`, `type`):

```json
{"id":"q61","q":"hoodie under RM 50","type":"constraint","filters":{"maxPrice":50,"category":"Apparel","type":"product"},"relevant":[]}
```

Run against a backend with the `dev` profile (otherwise `mode` and `debug` are ignored) and the demo catalogue:

```bash
python3 eval/run_retrieval_eval.py --base http://127.0.0.1:8080 --modes keyword --pricing eval/pricing.json --out eval/results/retrieval-$(date +%F).md
```

Exit codes: 0 done; 2 more than 5 % of requests failed in some mode; 3 some queries are still unlabelled
(this is the expected result for `queries.jsonl` until it is labelled).

## Assistant (P4b)

`assistant_questions.jsonl` and `run_assistant_eval.py`: see the script's docstring.
