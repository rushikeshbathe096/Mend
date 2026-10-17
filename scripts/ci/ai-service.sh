#!/usr/bin/env bash
# CI: ai-service tests in a fresh virtualenv built from requirements.txt (never the developer's .venv).
# AI_SERVICE_VENV picks the venv directory (default: a new temporary directory, removed on exit).
set -euo pipefail
cd "$(dirname "$0")/../../ai-service"
PYTHON="${PYTHON:-python3}"
if [[ -n "${AI_SERVICE_VENV:-}" ]]; then
  venv="$AI_SERVICE_VENV"
else
  venv="$(mktemp -d)/venv"
  trap 'rm -rf "$(dirname "$venv")"' EXIT
fi
"$PYTHON" -m venv "$venv"
"$venv/bin/python" -m pip install --quiet --upgrade pip
"$venv/bin/python" -m pip install --quiet -r requirements.txt
"$venv/bin/python" -m pytest -q
