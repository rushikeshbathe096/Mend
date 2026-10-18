# Retrieval-augmented reply parsing — gated experiment (M4 U4.7, ADR-004 §8)

**Status: built, unit-tested, DISABLED. Metrics TBD.** `mend.reply.retrieval.enabled=false` (env
`MEND_REPLY_RETRIEVAL_ENABLED`). No improvement is claimed.

## What exists
- **Store:** `reply_label_examples` (V26) in PostgreSQL. It holds text, intent, an optional promised date, the dataset
  version and the source. The source CHECK allows only `SYNTHETIC` or `CONSENTED`. The table is append-only, and its
  `tsvector` column (`'simple'` configuration, no stemming) has a GIN index. It is never filled from `customer_replies`.
- **Retrieval:** `ReplyExampleStore.similar(text, k)` ranks rows with `ts_rank` over an OR of the reply's lexemes, ties
  by id. k ≤ 5 (default 3). There is no vector DB and no new service.
- **Job:** with the flag on, `ReplyParsingJob` sends `parse(text, examples)`. With it off, the call is exactly the M3
  `parse(text)` (tested).
- **ai-service:** with examples present, the prompt is `reply-parse-v2-fewshot`. The examples are JSON lines marked as
  data, at most 5, and validated: an intent enum, an ISO date, no extra fields. Without examples, the prompt and its
  version `reply-parse-v1` are unchanged.
- **Harness:** `python -m eval.retrieval_eval [--dataset replies-v2.jsonl] [--examples export.jsonl]` compares the
  keyword baseline, the model and the model with retrieval, and applies the adoption rule as code
  (`adoption_decision`).

## Adoption rule
Turn the flag on only if both conditions hold on the **blind** `docs/m3/eval/replies-v2.jsonl` (written without sight of
the keyword rules):
- model+retrieval macro F1 > model macro F1;
- invalid-output rate(model+retrieval) ≤ invalid-output rate(model).

## Current result (`docs/m4/eval/retrieval-results.json`)
`KEEP_DISABLED`: `replies-v2` does not exist yet and there is no `GEMINI_API_KEY` (presence checked only). The baseline,
model and model+retrieval figures are all **TBD**.

## To run it for real
1. Write `replies-v2.jsonl` blind: a labeller who has not seen the rules, following `docs/m3/eval/LABEL-GUIDE.md`.
2. Load the SYNTHETIC dev split into the store (`ReplyExampleStore.importExamples`) and export k examples per v2 row.
3. Run the harness with a key.

Adopt only on a measured `ADOPT`, recorded with the dataset sha256 and the reported model id.
