# Reply label guide (dataset `replies-v1`)

`replies-v1` has two splits:
- `replies-v1-dev.jsonl` (60 rows): the keyword baseline's rules were written while looking at it, so its baseline
  score is an upper bound, not a measurement.
- `replies-v1-test.jsonl` (40 rows): held out. It was written after the rules were frozen, and the rules were not
  changed afterwards. Headline numbers come from this split. The same author wrote both splits, which limits how
  independent the test split is.

Every row in both files is **SYNTHETIC**. The replies were written for this evaluation; they are not customer
data and not real replies. The metrics measure behaviour on this dataset only, not production accuracy.

## Labels
One `intent` per reply, matching the backend enum `ReplyIntent`:

| intent | when |
|---|---|
| `OPT_OUT` | asks to stop being contacted |
| `ALREADY_PAID` | says the payment was already made |
| `DISPUTE` | disputes the charge, or says it is wrong, unauthorized or not delivered |
| `PROMISE_TO_PAY` | says they will pay; `promised_date` is set only when an exact ISO date is named |
| `OTHER` | anything else (questions, greetings, requests) |

Rules:
- If a reply both disputes and asks to stop, label `DISPUTE`: a dispute needs a human.
- Relative dates ("tomorrow", "Friday") get `promised_date: null`: the label never guesses a calendar date.
- Changing a label or adding a row creates a new dataset version (`replies-v2`). `replies-v1` is never edited.

## Known limitations of `replies-v1`
These are fixed only in a new version, never by editing v1.
- t026 "Can I pay in a couple of days?" is labelled PROMISE_TO_PAY. It is arguably a question, which would make it OTHER.
- No row exercises the "dispute that also asks to stop" rule.
- No gold label covers an unclear reply, so an abstention can only ever count as a miss.
- The test split was written by an author who knew the frozen keyword rules (see RESULTS.md).

