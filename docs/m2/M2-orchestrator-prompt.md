# M2 orchestrator prompt (paste into Claude Code)

```text
Read AGENTS.md, docs/m2/ (M2-execution-safety.md, M2-design-doc.md, M2-implementation-plan.md),
docs/adr/ADR-002-execution-safety.md, and docs/m1/M1-summary.md. You are the M2 orchestrator.
Create branch m2/execution-safety from main. Execute units in order:
U0 → U1 → U2 → U3 → U4 → U5 → U6 → U7 → U8 → U9 → U10.
Work autonomously; do not ask permission between units.

FOR EACH UNIT:
1. Re-read the unit prompt in docs/m2/agent-prompts/ and docs/m2/m2-progress.md.
2. Implement. Commit "M2 Ux: <summary>".
3. Run ./mvnw test. Count must be >= previous unit's count, 0 failures, 0 errors.
   Skipped may only be: the M1 tracking test (until U8) + U1 RED tests not yet fixed.
4. REVIEW in a fresh subagent given ONLY: AGENTS.md, the unit prompt, M2-execution-safety.md §4–§7,
   and `git diff` of the unit. It must check the unit's REVIEW CHECKLIST plus M2 invariants 11–15:
   any status write without a fence predicate? any provider call without an applied dispatch?
   any path returning EXECUTING to READY other than SAFE_RETRY? any SAFE_RETRY for a
   non-resend-safe action? any test deleted/skipped/weakened? Fix real findings,
   commit "M2 Ux: review fixes".
5. Append to docs/m2/m2-progress.md: built, exact test counts, review findings+fixes, rejected
   findings with reasons, deviations, ADAPTATIONS for the next unit (written before applying).

ADAPTATION RULES:
- You MAY adapt implementation details, names, test structure, and add tests.
- You may NOT change: AGENTS.md invariants, the §4 state machine, the §7 decision table,
  ADR-002 decisions/owner decisions, or scope. Never edit those docs to make work fit.

HARD STOPS (stop the run and report):
- U0 finds a fact that invalidates a design decision (not just a line number)
- a RED test from U1 is still disabled at the start of U7
- a migration would need editing an existing migration, or V12/V13/V14 fail on existing data
- tests can't be made green within 3 attempts on a unit
- count would drop, or an existing test would need deleting/skipping
- U4: adding @Version breaks >10 tests needing refactors outside FILES TO TOUCH
- U8: existing tests rely on campaign-level correlation finalizing an intent
- any need to call a Razorpay endpoint not verified from official docs + recorded

FINAL: print m2-progress.md and the AGENTS.md final-report format for all completed units.
```

After it stops or finishes, paste `docs/m2/m2-progress.md` for review before merging.
