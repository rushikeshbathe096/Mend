#!/usr/bin/env bash
# M4 U4.3: create an evaluation snapshot on demand (ADR-004 §5). Needs a running backend and a SYSTEM_ADMIN token.
#   MEND_API_URL   (default http://localhost:8080)
#   MEND_TOKEN     bearer token of a SYSTEM_ADMIN user (never written to a file by this script)
# Usage: scripts/eval/snapshot.sh <window-from> <window-to> [as-of] [horizon-days] [merchant-id,...]
#   times are ISO-8601 instants, e.g. 2026-10-01T00:00:00Z; as-of defaults to window-to; horizon defaults to 14 days.
# Prints the snapshot metadata (id, sha256, status, errors). The files are written on the server under
# mend.eval.snapshot.dir/<id>/ (campaigns.jsonl, decisions.jsonl, campaigns.csv, manifest.json).
set -euo pipefail

if [[ $# -lt 2 ]]; then
  sed -n '2,9p' "$0" >&2
  exit 2
fi
: "${MEND_TOKEN:?set MEND_TOKEN to a SYSTEM_ADMIN bearer token}"
api="${MEND_API_URL:-http://localhost:8080}"
from="$1"; to="$2"; as_of="${3:-}"; horizon="${4:-}"; merchants="${5:-}"

json="{\"windowFrom\":\"$from\",\"windowTo\":\"$to\""
[[ -n "$as_of" ]] && json+=",\"asOf\":\"$as_of\""
[[ -n "$horizon" ]] && json+=",\"horizonDays\":$horizon"
if [[ -n "$merchants" ]]; then
  json+=",\"merchantIds\":[\"${merchants//,/\",\"}\"]"
fi
json+="}"

curl -fsS -X POST "$api/api/v1/internal/eval/snapshots" \
  -H "Authorization: Bearer $MEND_TOKEN" -H "Content-Type: application/json" \
  --data "$json"
echo
