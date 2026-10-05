# U0 — Baseline + Razorpay test-mode recordings (YOU, not an agent)

No production code changes. This unit produces the evidence every later unit relies on.

## Steps

1. Baseline: `cd backend && ./mvnw test 2>&1 | tee ../docs/m1/baseline-test-run.txt`
   Record in `M1-provider-correctness.md` §12: tests run / failures / errors / skipped. This is the floor U9 must meet.
2. Razorpay Dashboard → switch to **Test Mode** → API Keys → generate a test key pair (`rzp_test_...`).
3. `export RZP_KEY_ID=rzp_test_xxx RZP_KEY_SECRET=xxx`
4. `bash scripts/record_razorpay_contracts.sh`
   Writes fixtures to `backend/src/test/resources/razorpay/recorded/` and prints a summary table.
   It creates **2 payment links** (test mode allows 30 per business) and 2 orders.
5. Manual paid-link capture (needed for U5/U7 evidence fixtures):
   a. Open the `short_url` printed for `01_create_link_ok`.
   b. Pay with a Razorpay test card / test netbanking (Razorpay's test credentials page).
   c. `PLINK_ID=<id> PAY_ID=<pay id> bash scripts/record_razorpay_contracts.sh --after-payment`
      → records `20_fetch_link_paid.json` and `21_fetch_payment_captured.json`.
   d. If you have a public webhook URL (ngrok → your local `/api/v1/webhooks/razorpay`, or a request-bin), subscribe to payment link + payment events in test mode and save the raw delivered body for the link-paid event as `recorded/22_webhook_link_paid.json`. **This resolves the `payment_link.paid` AMBIGUOUS row.**
6. Optional — recurring (only if your test account has recurring/cards enabled and you completed a card mandate authorisation in test mode):
   `RZP_TEST_CUSTOMER_ID=cust_x RZP_TEST_TOKEN_ID=token_x RZP_TEST_EMAIL=... RZP_TEST_CONTACT=... bash scripts/record_razorpay_contracts.sh --recurring`
   If you cannot do this, U8 is skipped and `RETRY_PAYMENT` stays NOT_SUPPORTED. That is a valid M1 outcome.
7. Review every fixture by eye for PII before committing. Commit fixtures + summary.
8. Update the verdict table in `M1-provider-correctness.md` §5: set "RECORDED" where a fixture now exists; resolve the `receipt` uniqueness row from `08_create_order_same_receipt.json`.

## Exit
- baseline numbers written down
- ≥ 10 fixtures committed
- AMBIGUOUS rows updated (or explicitly left AMBIGUOUS with reason)

> Note (owner decision D5, 2026-10-05): raw Maven logs under `docs/` are local-only (gitignored). Each has a tracked `<name>-summary.txt` next to it (command, date, commit, totals, BUILD result, per-class lines); reference the summary.
