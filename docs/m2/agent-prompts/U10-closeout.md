# U10 — M2 close-out

1. Full suite ×1: write `docs/m2/m2-final-test-run-summary.txt` (totals + per-class). Count ≥ 519 + new; Skipped 0.
2. Query plans: seed 100k intents (realistic status mix, 5k merchants) in a throwaway DB/Testcontainer; `EXPLAIN (ANALYZE, BUFFERS)` for: due-claim select, claim update, release, markStuck, reconciler pick (SKIP LOCKED), one-in-flight NOT EXISTS. Record plans + timings in `docs/m2/M2-query-plans.md`. Label MEASURED with machine spec; no targets claimed as results.
3. Verify the §9 failure table: each row → test name that proves it. Missing → write the test or mark TBD with reason.
4. Tick `M2-implementation-plan.md` with commit SHAs. ADR-002 → Accepted, Evidence filled. `docs/m2/M2-summary.md` (≤ 15 lines) incl. "M3 inputs" and "still deferred".
5. Update AGENTS.md "Current milestone" to "M2 complete — next: M3 (do not start without a design pack)".
Agent rule: copy numbers from files; never retype or estimate.
