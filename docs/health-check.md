# Pre-M1 Health Check

Date: 2026-10-05 · Branch: `pre-m1-health-check` (from `main` @ `839f049`) · Environment: `mend-postgres` (5433) and `mend-redis` (6379) are **Up (healthy)**.

- **Part 1:** baseline, diagnosis, and 3 tenant-isolation fixes.
- **Part 2:** live auth smoke test, real browser login through the frontend dev server, frontend and ai-service checks. Part 2 found no new functional breakage, so it changed no backend code and did not rerun the full suite.

## Issues

| # | Issue | Evidence | Root cause | Status | Commit |
|---|---|---|---|---|---|
| 1 | Cross-tenant read: `GET /agent/timeline?merchantId={other}` | Before: A's token → 200 with B's records. After: 403 `Access denied for merchant: c7a5…` | `AgentDecisionQueryController.java:46-48` (pre-fix) used the query param without checking membership. `SecurityFilter` only validates the `X-Merchant-Id` header. | **Fixed**. Test `timelineQueryParamCannotSelectForeignMerchant` | `d156cca` |
| 2 | Cross-tenant read: `GET /campaigns/{other's id}/decisions` | Before: 200 with B's records. After: 200 `[]` (B sees 3) | `AgentDecisionQueryController.java:30-36` (pre-fix) queried by `campaignId` only | **Fixed**. Test `campaignDecisionsAreScopedToCallerMerchant` | `2d8d332` |
| 3 | Cross-tenant read: `GET /audit/campaign/{other's id}` | Before: 200 with B's audit rows. After: 200 `[]` (B sees 4) | `AuditService.java:115-118` (pre-fix) checked the caller's own merchant, then queried by `campaignId` only | **Fixed**. Test `campaignAuditLogsAreScopedToCallerMerchant` | `5052719` |
| 4 | `POST /auth/bootstrap` creates a **new merchant** on every call, even for an existing admin. The login page pre-fills `admin@acme.com`, so each click on "bootstrap" adds a duplicate "Acme Enterprise". Afterwards `api.ts` picks `memberships[0]`, which is the new empty merchant. | Part 2 call → 201 `merchantId 22c92e59…` (existing Acme is `c7a5d49b…`). `/auth/me` for admin@acme.com now lists 2 memberships, and a browser login lands on `22c92e59…`. | `AuthService.java:160` always runs `new Merchant(...)`. Defaults at `login/page.tsx:21`; selection at `lib/api.ts:51`. | **Not fixed**: unclear whether this is intended (see §4) | — |
| 5 | AI agent tools call the backend with no auth and at paths that don't exist | ai-service log: 401 on `/webhooks/events`, `/campaigns/{id}/attempts`, `/merchants/config`. The first two exist in no controller. | `ai-service/agent/mcp_tools.py:26,105,122`, `agent/tools.py:15` | Not fixed: M3 | — |
| 6 | Razorpay `/payments/{id}/retry` and `/payments/retry` don't exist; timeout/5xx → FAILED | `docs/m1/baseline-triage.md` | `RazorpayPaymentProviderClient.java:121,125` | Not fixed: M1 | — |
| 7 | `docs/` is gitignored, so AGENTS.md's `docs/m1/*` references and this report are untracked. README links `docs/SETUP.md` and `docs/DEMO.md`, which don't exist. | `git ls-files docs` → empty | `.gitignore:27` | Not fixed: §4 | — |
| 8 | `.env.example` uses compose-network hosts, but the backend isn't in compose | `postgres:5432`, `REDIS_HOST=redis`. Nothing loads `.env` into Spring, so the host run falls back to `localhost:5433` and works. | `.env.example:14,20` | Not fixed: §4 | — |
| 9 | Frontend lint errors | `✖ 64 problems (53 errors, 11 warnings)`. Build, tsc and jest all pass. | style rules (`no-explicit-any`, `set-state-in-effect`, …) | Not broken. Not fixed. | — |

## Evidence: Part 2

### 1. Live auth smoke test (dev profile, `curl`)
A = `admin@testmerchant.com` / merchant `8d919a7f…`; B = `admin@acme.com` / merchant `c7a5d49b…`.

| Probe | Status | Body (truncated) |
|---|---|---|
| `GET /api/v1/health` | 200 | `{"status":"UP","service":"mend-backend"}` |
| bootstrap, existing admin + correct password | 201 | `{"merchantId":"22c92e59…","merchantName":"Acme Enterprise","userEmail":"admin@acme.com","roleName":"MERCHANT_ADMIN"}` (see issue 4) |
| bootstrap, existing admin + wrong password | 401 | `Invalid password for existing admin account` |
| login, wrong password | 401 | `Invalid email or password` |
| login A, login B | 200 | token (313 / 292 chars) |
| `GET /campaigns` with a valid token | 200 | `{"content":[{"attemptCount":3,…` |
| `GET /campaigns` with no token | 401 | `Missing or invalid Authorization header` |
| `GET /campaigns` with the signature changed | 401 | `Invalid or expired token` |
| `GET /campaigns` with the payload changed (`sub` = B's user) | 401 | `Invalid or expired token` |
| `GET /campaigns` with `alg: none` | 401 | `Invalid or expired token` |
| A: `GET /campaigns` + `X-Merchant-Id: B` | 403 | `Access denied: user is not a member of the specified merchant header` |
| A: `GET /campaigns/{B}` | 404 | `Campaign not found for merchant 8d91…` |
| A: `GET /payments/{B}` | 404 | `Payment/Campaign not found…` |
| A: `GET /campaigns/{B}/decisions` | 200 | `[]` |
| A: `GET /audit/campaign/{B}` | 200 | `[]` |
| A: `GET /agent/timeline?merchantId=B` | 403 | `Access denied for merchant: c7a5…` |
| A: `GET /merchants/{B}/members` | 403 | `Access denied to merchant c7a5…` |
| A: `POST /campaigns/{B}/pause` | 403 | `Campaign 3f57… does not belong to merchant 8d91…` |
| CORS preflight `POST /auth/login` from `http://localhost:3000` | 200 | `Allow-Origin: http://localhost:3000`, `Allow-Methods: GET,POST,PUT,DELETE,OPTIONS`, `Allow-Headers: content-type` |
| CORS preflight from `http://evil.example` | 403 | — |

### 2. Frontend
- `npm ci`: exit 0. `npm run build`: OK. `npx tsc --noEmit`: exit 0. `npx jest`: `Test Suites: 13 passed, 13 total`, `Tests: 33 passed, 33 total`. `npm run lint`: `✖ 64 problems (53 errors, 11 warnings)` (issue 9). There is no `typecheck` script, so I ran `tsc` directly.
- **Paths:** every call site in `src/` (excluding tests) maps to a backend mapping. The checked paths: `/auth/login`, `/auth/bootstrap`, `/analytics/{overview,funnel,recovery,failure-breakdown,strategy-performance}`, `/campaigns`, `/campaigns/{id}`, `/campaigns/{id}/{decisions,timeline}`, POST `/campaigns`, `/campaigns/start-recovery`, `/campaigns/{id}/{pause|terminate}`, `/customers`, `/customers/{id}`, `/payments`, `/payments/{id}`, `/recovery/actions`, `/reviews`, `/reviews/summary`, `/reviews/{id}/{approve|reject}`, `/webhooks`, `/audit`, `/merchants/config` (GET and PUT), `/demo/scenarios`, `/demo/trigger-scenario`.
- **Real browser login:** headless Chrome via Playwright against `next dev` on :3000 and the backend on :8080.
  - The login form submits, the browser redirects to `/dashboard`, and localStorage holds `mend_token` and `mend_merchant_id`.
  - All routes render with their `<h1>`: `/campaigns`, `/actions`, `/recovery-actions` (→ `/actions`), `/payments`, `/customers`, `/webhooks`, `/audit`, `/analytics`, `/settings`, `/demo`, `/campaigns/{id}`.
  - All 21 unique backend calls returned **200**.
  - **0** console errors, page errors or failed requests, which means no CORS or token errors. Repeating the run as admin@acme.com also gave 0 errors.

### 3. ai-service
- A venv in the scratchpad, `pip install -r requirements.txt`: OK. `import main`: OK. `pytest`: `51 passed in 1.98s`.
- `uvicorn main:app --port 8000`, then `GET /health`: 200 `{"status":"UP","service":"mend-ai-service","model_provider":"BOUNDED_HEURISTIC","llm_enabled":false}`.
- Side effect: pytest rewrites the timing values in `performance_benchmark_results.json` and `recovery_effectiveness_benchmark.json`. I reverted both.

## Final report

### 1. Baseline vs final backend numbers (copied from output)
- Baseline (`docs/m1/baseline-test-run-summary.txt`; raw log local-only): `Tests run: 320, Failures: 0, Errors: 0, Skipped: 0`
- After fixes 1–3 (`docs/health-check-after-summary.txt`; raw log local-only): `Tests run: 323, Failures: 0, Errors: 0, Skipped: 0`, BUILD SUCCESS
- Fail-before check: with `backend/src/main` reverted to `839f049`, `CrossTenantReadIsolationIntegrationTest` gave `Tests run: 3, Failures: 3`.

### 2. Fixed
Issues 1–3 (see the table). All three are in `backend/src/test/java/com/mend/integration/CrossTenantReadIsolationIntegrationTest.java`. Each fix adds one derived query method or one membership check. No schema changes and no new dependencies.

### 3. Owned by a later milestone
Issue 5 → M3. Issue 6 → M1. The lease-expiry duplicate-call boundary (README "Limitations") → M2.

### 4. Uncertain or risky (decision needed)
- **Issue 4 (bootstrap duplicates):**
  - It may be intended as "create another merchant for this admin", but the pre-filled login page makes duplicates easy and switches the admin's default console to an empty merchant.
  - Options: (a) bootstrap returns the existing membership when the user already belongs to a merchant with that name, (b) dev-only, (c) leave it as is.
  - **Dev DB state:** my Part 2 smoke test created merchant `22c92e59-898f-46a8-a54f-b52d7b721192`. I checked every `merchant_id` column: the only rows referencing it are 1 in `merchant_users` and 1 in `merchant_config` (created when the browser walk loaded `/settings`). Not deleted; to remove them:
    `DELETE FROM merchant_config WHERE merchant_id='22c92e59-898f-46a8-a54f-b52d7b721192'; DELETE FROM merchant_users WHERE merchant_id='22c92e59-898f-46a8-a54f-b52d7b721192'; DELETE FROM merchants WHERE id='22c92e59-898f-46a8-a54f-b52d7b721192';`
- **Issue 7:** whether to un-ignore and commit `docs/` (or `docs/m1`, `docs/adr`, `docs/health-check*`). A clean clone currently has no M1 spec.
- **Issue 8:** whether the backend will join compose (M5). If not, point the template at `localhost:5433` / `localhost`.
- **Issue 9:** whether lint must be clean before CI (M5).

### 5. Changes outside the BROKEN definition
None.
