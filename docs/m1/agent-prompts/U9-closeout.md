# U9 — M1 close-out (you + agent)

1. `cd backend && ./mvnw test 2>&1 | tee ../docs/m1/m1-final-test-run.txt`. Compare with `baseline-test-run.txt`: total ≥ baseline, 0 failures.
2. Fill `M1-provider-correctness.md` §12 with both summaries (numbers copied from the files, not retyped from memory).
3. Re-open the DOC URLs in §4; if any changed, update §4/§5 and note the date.
4. Tick `M1-implementation-plan.md` checklist rows with the commit SHA that satisfied each.
5. ADR-001: fill Evidence (fixture names, test counts), set Status: Accepted.
6. Run the grep gates from U1, U6, U8.
7. Write a 10-line `docs/m1/M1-summary.md`: what changed, what is still NOT done (debits, fencing, per-merchant credentials), and what M2 must start with (the lease-expiry-during-provider-call test).

Agent prompt for steps 2, 4, 5, 7: "Using only the contents of docs/m1/*.txt test outputs and git log, fill the TBD fields. Do not invent numbers. If a value is missing, write TBD."

> Note (owner decision D5, 2026-10-05): raw Maven logs under `docs/` are local-only (gitignored). Each has a tracked `<name>-summary.txt` next to it (command, date, commit, totals, BUILD result, per-class lines); reference the summary.
