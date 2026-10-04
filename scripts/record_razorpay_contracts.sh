#!/usr/bin/env bash
# Records real Razorpay TEST-MODE responses as contract fixtures for MEND M1.
#
# Usage:
#   RZP_KEY_ID=rzp_test_xxx RZP_KEY_SECRET=xxx bash scripts/record_razorpay_contracts.sh
#   PLINK_ID=plink_x PAY_ID=pay_x ... bash scripts/record_razorpay_contracts.sh --after-payment
#   RZP_TEST_CUSTOMER_ID=cust_x RZP_TEST_TOKEN_ID=token_x RZP_TEST_EMAIL=a@b.c RZP_TEST_CONTACT=9000000000 \
#     ... bash scripts/record_razorpay_contracts.sh --recurring
#
# Requires: bash, curl, jq. Refuses live keys. Redacts customer PII before writing.
# Creates 2 payment links per default run (Razorpay test mode allows 30 per business).

set -euo pipefail

MODE="${1:-default}"
BASE="${RZP_BASE_URL:-https://api.razorpay.com}"
OUT_DIR="${OUT_DIR:-backend/src/test/resources/razorpay/recorded}"

command -v jq >/dev/null || { echo "jq is required"; exit 1; }
command -v curl >/dev/null || { echo "curl is required"; exit 1; }
: "${RZP_KEY_ID:?set RZP_KEY_ID}"
: "${RZP_KEY_SECRET:?set RZP_KEY_SECRET}"

case "$RZP_KEY_ID" in
  rzp_test_*) ;;
  *) echo "REFUSING: RZP_KEY_ID is not a test key (must start with rzp_test_)"; exit 2 ;;
esac

mkdir -p "$OUT_DIR"
RUN_ID="$(date +%s)"
SUMMARY=()

# Redact PII-bearing fields anywhere in the JSON tree.
redact() {
  jq 'walk(if type == "object" then
        (if (.email   | type) == "string" then .email   = "<redacted>" else . end)
      | (if (.contact | type) == "string" then .contact = "<redacted>" else . end)
      | (if (.vpa     | type) == "string" then .vpa     = "<redacted>" else . end)
      | (if (.name | type) == "string" and (has("email") or has("contact")) then .name = "<redacted>" else . end)
      else . end)' 2>/dev/null || cat
}

# Milliseconds since epoch (portable: GNU date supports %N, BSD/macOS date does not).
now_ms() {
  local t; t="$(date +%s%3N 2>/dev/null || true)"
  if [[ "$t" =~ ^[0-9]+$ ]]; then echo "$t"; else echo $(( $(date +%s) * 1000 )); fi
}

# rec <fixture_name> <METHOD> <path> [json_body] [auth_override]
rec() {
  local name="$1" method="$2" path="$3" body="${4:-}" auth="${5:-$RZP_KEY_ID:$RZP_KEY_SECRET}"
  local tmp; tmp="$(mktemp)"
  local start end status
  start=$(now_ms)
  if [[ -n "$body" ]]; then
    status=$(curl -sS -o "$tmp" -w '%{http_code}' -u "$auth" -X "$method" \
      -H 'Content-Type: application/json' -H 'Accept: application/json' \
      --max-time 30 "$BASE$path" -d "$body") || true
  else
    status=$(curl -sS -o "$tmp" -w '%{http_code}' -u "$auth" -X "$method" \
      -H 'Accept: application/json' --max-time 30 "$BASE$path") || true
  fi
  end=$(now_ms)
  status="${status:-000}"; status=$((10#${status:0:3}))   # 0 = transport failure

  local resp_json
  if jq -e . "$tmp" >/dev/null 2>&1; then resp_json="$(redact < "$tmp")"; else resp_json="$(jq -Rs . < "$tmp")"; fi
  local req_json="null"
  [[ -n "$body" ]] && req_json="$(echo "$body" | redact)"

  jq -n --arg name "$name" --arg method "$method" --arg path "$path" \
        --argjson reqBody "$req_json" --argjson status "$status" --argjson respBody "$resp_json" \
        --arg recorded_at "$(date -u +%Y-%m-%dT%H:%M:%SZ)" --argjson latency_ms "$((end-start))" \
     '{fixture:$name, mode:"test", recorded_at:$recorded_at, latency_ms:$latency_ms,
       request:{method:$method, path:$path, body:$reqBody},
       response:{status:$status, body:$respBody}}' > "$OUT_DIR/$name.json"
  rm -f "$tmp"
  SUMMARY+=("$(printf '%-38s %-6s %-48s -> %s (%sms)' "$name" "$method" "$path" "$status" "$((end-start))")")
  echo "$status"
}

# field <fixture> <jq expr relative to response body>; empty if body is not an object
field() { jq -r '.response.body | (if type == "object" then . else {} end) | '"$2" "$OUT_DIR/$1.json"; }

if [[ "$MODE" == "default" ]]; then
  REF="mnd_rec_${RUN_ID}"             # <= 40 chars
  LINK_OK=$(jq -n --arg ref "$REF" '{amount:100, currency:"INR", reference_id:$ref,
      description:"MEND M1 contract recording", notify:{sms:false,email:false},
      reminder_enable:false, notes:{mend_intent_id:"recording"}}')

  rec 01_create_link_ok            POST "/v1/payment_links" "$LINK_OK" >/dev/null
  rec 02_create_link_duplicate_ref POST "/v1/payment_links" "$LINK_OK" >/dev/null
  rec 03_create_link_extra_fields  POST "/v1/payment_links" \
      "$(jq -n --arg ref "${REF}_x" '{amount:100,currency:"INR",reference_id:$ref,receipt:"rcpt_1",idempotency_key:"k",payment_id:"pay_x"}')" >/dev/null
  rec 04_create_link_missing_amount POST "/v1/payment_links" \
      "$(jq -n --arg ref "${REF}_y" '{currency:"INR",reference_id:$ref}')" >/dev/null

  PLINK=$(field 01_create_link_ok '.id // empty')
  if [[ -n "$PLINK" ]]; then
    rec 05_fetch_link_by_id        GET "/v1/payment_links/$PLINK" >/dev/null
  fi
  rec 06_fetch_link_by_reference   GET "/v1/payment_links?reference_id=$REF" >/dev/null

  RCPT="mnd_rcpt_${RUN_ID}"
  ORDER=$(jq -n --arg r "$RCPT" '{amount:100,currency:"INR",receipt:$r,payment_capture:true,notes:{mend_intent_id:"recording"}}')
  rec 07_create_order_ok           POST "/v1/orders" "$ORDER" >/dev/null
  rec 08_create_order_same_receipt POST "/v1/orders" "$ORDER" >/dev/null   # resolves receipt-uniqueness AMBIGUOUS
  OID=$(field 07_create_order_ok '.id // empty')
  [[ -n "$OID" ]] && rec 09_fetch_order_payments GET "/v1/orders/$OID/payments" >/dev/null

  # Legacy endpoints currently called by MEND — expected to fail; recorded as evidence.
  rec 10_legacy_payment_retry      POST "/v1/payments/pay_M1doesnotexist/retry" '{"receipt":"rcpt_1"}' >/dev/null
  rec 11_legacy_subscription_charge POST "/v1/subscriptions/sub_M1doesnotexist/charge" '{"receipt":"rcpt_1"}' >/dev/null
  rec 12_legacy_payments_retry     POST "/v1/payments/retry" '{"receipt":"rcpt_1"}' >/dev/null

  rec 13_fetch_payment_not_found   GET "/v1/payments/pay_M1doesnotexist" >/dev/null
  rec 14_bad_auth                  GET "/v1/payment_links/${PLINK:-plink_x}" "" "rzp_test_invalid:invalid" >/dev/null

  echo
  echo "Open this link and pay with a Razorpay test instrument, then run --after-payment:"
  echo "  short_url: $(field 01_create_link_ok '.short_url // "n/a"')"
  echo "  PLINK_ID=$PLINK"

elif [[ "$MODE" == "--after-payment" ]]; then
  : "${PLINK_ID:?set PLINK_ID}"
  rec 20_fetch_link_paid GET "/v1/payment_links/$PLINK_ID" >/dev/null
  PAY="${PAY_ID:-$(field 20_fetch_link_paid '(.payments // [])[0].payment_id // empty')}"
  [[ -n "$PAY" ]] && rec 21_fetch_payment_captured GET "/v1/payments/$PAY" >/dev/null

elif [[ "$MODE" == "--recurring" ]]; then
  : "${RZP_TEST_CUSTOMER_ID:?}"; : "${RZP_TEST_TOKEN_ID:?}"; : "${RZP_TEST_EMAIL:?}"; : "${RZP_TEST_CONTACT:?}"
  rec 30_fetch_token GET "/v1/customers/$RZP_TEST_CUSTOMER_ID/tokens/$RZP_TEST_TOKEN_ID" >/dev/null
  RORDER=$(jq -n --arg r "mnd_rec_${RUN_ID}" '{amount:100,currency:"INR",receipt:$r,payment_capture:true}')
  rec 31_create_debit_order POST "/v1/orders" "$RORDER" >/dev/null
  DOID=$(field 31_create_debit_order '.id // empty')
  if [[ -n "$DOID" ]]; then
    RPAY=$(jq -n --arg o "$DOID" --arg c "$RZP_TEST_CUSTOMER_ID" --arg t "$RZP_TEST_TOKEN_ID" \
                 --arg e "$RZP_TEST_EMAIL" --arg p "$RZP_TEST_CONTACT" \
       '{email:$e, contact:$p, amount:100, currency:"INR", order_id:$o, customer_id:$c, token:$t,
         recurring:true, description:"MEND M1 recurring recording", notes:{mend_intent_id:"recording"}}')
    rec 32_create_recurring_payment POST "/v1/payments/create/recurring" "$RPAY" >/dev/null
    rec 33_fetch_order_payments_after_debit GET "/v1/orders/$DOID/payments" >/dev/null
    echo "Re-run fixture 33 later (or manually) to capture the terminal state of the debit."
  fi
else
  echo "unknown mode: $MODE"; exit 1
fi

echo
echo "Recorded fixtures (in $OUT_DIR):"
printf '%s\n' "${SUMMARY[@]}"
echo
echo "Review every file for PII before committing."
