# Reply-parsing evaluation — `replies-v1` (SYNTHETIC)

Run: `cd ai-service && python -m eval.run_eval`. Writes `results-replies-v1.json` (dataset sha256 per split, per-class
P/R/F1, macro F1, confusion matrix, invalid-output and abstention rates, promised-date exact match). Labels and splits:
`LABEL-GUIDE.md`. All rows are synthetic: numbers describe this dataset, not production.

## Keyword baseline `keyword-baseline-v1`, held-out `test` split (40 rows, headline)
| intent | precision | recall | F1 | support |
|---|---|---|---|---|
| OPT_OUT | 0.0 | 0.0 | 0.0 | 8 |
| ALREADY_PAID | 1.0 | 0.125 | 0.2222 | 8 |
| DISPUTE | 0.0 | 0.0 | 0.0 | 8 |
| PROMISE_TO_PAY | 0.0 | 0.0 | 0.0 | 8 |
| OTHER | 0.2051 | 1.0 | 0.3404 | 8 |

Macro F1 **0.1125**. Invalid outputs 0.0, abstentions 0.0. Promised dates
0/8.
On the `dev` split the rules were written against, macro F1 is 1.0. That is an upper bound, not a
measurement. The gap shows that keyword rules do not generalize to new phrasing.

## Model (`reply_parser`, prompt `reply-parse-v1`)
**TBD**: no GEMINI_API_KEY in the environment; the model was not run. No model number is recorded until a run with a key writes one
(same command).
