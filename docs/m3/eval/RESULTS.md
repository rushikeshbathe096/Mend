# Reply-parsing evaluation — `replies-v1` (SYNTHETIC)

Run: `cd ai-service && python -m eval.run_eval`. This writes `results-replies-v1.json` with, per split:
- the dataset sha256;
- per-class P/R/F1 and macro F1;
- the confusion matrix;
- invalid-output and abstention rates;
- promised-date exact match, dated and undated golds apart;
- provider errors, counted apart.

Labels and splits are described in `LABEL-GUIDE.md`. All rows are synthetic, so the numbers describe this dataset, not
production. Conventions: a class that is never predicted has precision 0.0, and macro F1 is the mean of the rounded
per-class F1s.

## Keyword baseline `keyword-baseline-v1`, `test` split (40 rows)
| intent | precision | recall | F1 | support |
|---|---|---|---|---|
| OPT_OUT | 0.0 | 0.0 | 0.0 | 8 |
| ALREADY_PAID | 1.0 | 0.125 | 0.2222 | 8 |
| DISPUTE | 0.0 | 0.0 | 0.0 | 8 |
| PROMISE_TO_PAY | 0.0 | 0.0 | 0.0 | 8 |
| OTHER | 0.2051 | 1.0 | 0.3404 | 8 |

| metric | value |
|---|---|
| macro F1 | **0.1125** |
| invalid outputs | 0.0 |
| abstentions | 0.0 |
| promised dates, dated golds | 0/3 |
| promised dates, undated golds | 0/5 |

### How to read these numbers
- **dev split.** The rules were written against it, so its macro F1 of 1.0 is an upper bound, not a
  measurement.
- **test split.** It was written after the rules were frozen, by the same author, who knew those rules. Almost every
  row avoids the rules' keywords: 39 of 40 rows fall through to OTHER. So 0.1125 is close to an *adversarial
  lower bound* for keyword matching, not a fair held-out estimate.
- **What this shows.** The baseline does not cover phrasing outside its keyword list. It does not show how well keyword
  rules generalize in general.
- **Model comparisons.** Compare a model against the baseline only on a future `replies-v2` written without sight of the
  rules.

## Model (`reply_parser`, prompt `reply-parse-v1`)
**TBD**: no GEMINI_API_KEY in the environment; the model was not run. No model number is recorded until a run with a key writes one, using
the same command. Provider failures in that run are counted as `provider_errors` and left out of the metrics.
