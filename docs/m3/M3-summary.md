# M3 summary — honest intelligence (ADR-003 Accepted)
1. **Payment path is deterministic.** No ai-service call on it: `AiOffHotPathTest` (unreachable address, run to RECOVERED, no AI bean).
2. **Failure taxonomy.** Pure Java (`com.mend.taxonomy`), 37 documented Razorpay `error_reason`s in 8 classes, plus UNCLASSIFIED, Razorpay docs cited; unmapped → UNCLASSIFIED.
3. **Confidence gates removed.** A class without a taxonomy version (legacy, or no row) fails closed (`UNKNOWN_CLASSIFICATION`).
4. **AI theatre deleted.** Agents, the orchestration/decision endpoints, the classify client, fake confidences, model ids and narration are gone. Legacy rows are kept untouched.
5. **One AI task.** Async customer-reply parsing (V20/V21, `ReplyParsingJob`): strict validator, deterministic `ReplyPolicy`.
6. **No AI financial action.** OPT_OUT is a compliance block, also read at dispatch. ALREADY_PAID only brings a link lookup forward. DISPUTE → review. No AI output can create, approve, resend or finalize an intent, or mark anything recovered.
7. **Honest model ids.** The model id is the provider's own; no key → `model_unavailable`, no output. Replies are PII: tenant-guarded, never logged or audited (tested), 90-day retention (purge is M5).
8. **Evaluation.** SYNTHETIC `replies-v1`. Keyword baseline macro F1 1.0 on dev (upper bound) and 0.1125 on the held-out test split (close to an adversarial lower bound). Model: TBD (no key).
9. **ai-service.** `/health` + `/api/v1/parse_reply` only; deps are fastapi, uvicorn, pydantic, httpx, pytest.
10. **Tests.** Backend 941/0/0/0 (was 813), frontend 62 (was 53), ai-service 90 (was 51): `docs/m3/m3-final-test-run-summary.txt`.
11. **Follow-ups** (tag `m3-followups`). All DB sessions run in UTC. Owner decision B: an UNCLASSIFIED decline gets a payment link, never a retry; the strategy table and compliance rule 3c each enforce this. Pre-M3 decisions cannot dispatch (`LEGACY_DECISION` → review). Backend 971/0/0/0.
12. **Out of scope.** Inbound reply channel; a "resolve, don't collect" review kind (M4); reply purge, metrics (M5); a model eval run with a key.
