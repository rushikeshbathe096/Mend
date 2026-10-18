# Retrieval-augmented reply parsing — gated experiment (M4 U4.7, ADR-004 §8)

**Status: built, unit-tested, DISABLED. Metrics TBD.** `mend.reply.retrieval.enabled=false` (env
`MEND_REPLY_RETRIEVAL_ENABLED`). No improvement is claimed.

## What exists
- **Store:** `reply_label_examples` (V26) in PostgreSQL. It holds text, intent, an optional promised date, the dataset
  version and the source. The source CHECK allows only `SYNTHETIC` or `CONSENTED`, and the service accepts only
  `SYNTHETIC` until consent capture exists. Evaluation datasets (`*-test`, `replies-v2`) are refused at import. The table is append-only, and its
  `tsvector` column (`'simple'` configuration, no stemming) has a GIN index. It is never filled from `customer_replies`.
- **Retrieval:** `ReplyExampleStore.similar(text, k)` searches only `mend.reply.retrieval.dataset-version` (default
  `replies-v1-dev`), one row per distinct text. It ranks with `ts_rank` over an OR of the reply's own lexemes, each one
  quoted, ties by text. k ≤ 5 (default 3). A retrieval failure falls back to parsing without examples. There is no vector
  DB and no new service.
- **Job:** with the flag on, `ReplyParsingJob` sends `parse(text, examples)`. With it off, the call is exactly the M3
  `parse(text)` (tested).
- **ai-service:** with examples present, the prompt is `reply-parse-v2-fewshot`. Each example is an `input:` JSON string
  plus an `output:` in the exact required shape, at most 5. The reply follows its own marker as a JSON string. Examples
  are validated: an intent enum, a real calendar date only with PROMISE_TO_PAY, no extra fields. Without examples, the prompt and its
  version `reply-parse-v1` are unchanged.
- **Harness:** `python -m eval.retrieval_eval [--dataset replies-v2.jsonl] [--examples export.jsonl]` compares the
  keyword baseline, the model and the model with retrieval, and applies the adoption rule as code
  (`adoption_decision`). It fails closed in each of these cases:
  - the dataset is not `replies-v2` (blindness is a property of the data, not of a file existing);
  - the two model systems were not scored on the same rows (provider errors);
  - any row lacks examples;
  - any example text equals an evaluation text (contamination);
  - the dataset contains duplicate texts.

  The report records the dataset sha256 and the SYNTHETIC label.

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
2. Load the SYNTHETIC dev split into the store (`ReplyExampleStore.importExamples("replies-v1-dev", "SYNTHETIC", …)`) and
   export k examples per v2 row. An export tool is not built yet (TBD, with the first real run).
3. Run the harness with a key.

Adopt only on a measured `ADOPT`, recorded with the dataset sha256 and the reported model id.
