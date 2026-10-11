# Cleanup plan (branch `cleanup/pre-m3`)

The group definitions come from the owner's cleanup prompt. That prompt was not saved to the repository while the work happened, so this file was written afterwards, at the end of the cleanup. Each group's scope below is taken from what `docs/audit/cleanup-report.md` records as the agreed scope and its preconditions. Where the two disagree, the report and the commits are authoritative.

Source audit: `docs/audit/unused-code-audit.md` (baseline 654 / 0 / 0 / 0 on `main` @ `b669205`).

## Rules for every group
- One commit per group. Run the full suite (`cd backend && ./mvnw test`) after each group and record the exact counts in the report.
- If a detail is unspecified, make the smallest change that keeps every invariant in `AGENTS.md`, and record the choice in the report.
- Mark a changed test assertion with `// cleanup behaviour change: <reason>`. Never weaken, skip or delete a test unless equivalent coverage replaces it.
- Out of scope: the AI path, tenant/membership checks, V1–V15, Phase-N test renames, and test package moves. Do not merge or push.

## Stop conditions (ask the owner)
1. Group 5: any `webhook_events` row has a failed publish status, in the dev DB or a test DB.
2. Group 6: V16 would fail on existing data, or code or the frontend still uses a value being removed.
3. The total test count would drop, or a "dead" item turns out to be used at runtime.
4. Anything suggests a product bug (deadlock, wrong money/state behaviour).
5. A change would touch the AI path, tenant/membership checks, or V1–V15.

## Groups

| # | commit subject | scope |
|---|---|---|
| 1 | `cleanup: remove unused enums` | Audit plan item 1: `AuditEventType`, `ErrorCategory`. |
| 2 | `cleanup: remove unused repository methods` | Audit plan item 2: the 12 repository methods that have no callers. |
| 3 | `cleanup: shared payment-link id helper` | Merge the duplicated `plink_` id check into one helper, and add a test for it. |
| 4 | `fix: honest amounts in analytics` | Make the strict M1 parser (`WebhookAmountResolver.extractMoneyMinor`) the only amount parser. Delete the lenient one and analytics' fabricated `1000.00` default. Sum `long` paise exactly. Report a missing or unparseable amount as unknown (`amountUnknownCount`), never as a number. Count "recovered" only when attribution is MEND, and report late capture and unknown attribution separately. Move Jackson 2 to test scope. Add regression tests. |
| 5 | `cleanup: remove legacy webhook publish chain` | Delete `RedisStreamConsumer`, the legacy handler, `WebhookPublisherRetryService`, the legacy publisher interface/no-op and `RedisWebhookEventPublisher.publish(WebhookEvent)`. Move the tests onto the outbox path with equivalent coverage. Precondition: no failed-publish rows (stop 1). |
| 6 | `cleanup: drop legacy status values + CHECK constraints` | Remove the status enum values that are never written. Add `V16` CHECK constraints that fail loudly on legacy data and never rewrite it. Remove the matching frontend cases. Precondition: stop 2. |
| 7 | `config: complete .env.example and safe default profile` | Without `SPRING_PROFILES_ACTIVE` the app runs a profile with no dev seed and no demo. `.env.example` lists every variable the code reads. Inline `@Value` defaults are listed in `application.properties`. |
| 8 | `frontend: M2 states and demo page` | Badge styles and filters for `EXECUTING` / `NEEDS_RECONCILIATION` / `AWAITING_PAYMENT`. Drop the dead `COMPLETED` check (audit plan item 3). The demo page shows "Demo disabled" when it gets a 404. |
| 9 | `docs: README index and limitations` | Audit plan item 4: fix the broken README links, drop the closed limitation, replace the frontend README boilerplate. |

Results, evidence and test counts per group: `docs/audit/cleanup-report.md`.
