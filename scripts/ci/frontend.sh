#!/usr/bin/env bash
# CI: frontend install (lockfile), lint (0 errors), unit tests, production build.
set -euo pipefail
cd "$(dirname "$0")/../../frontend"
npm ci --no-audit --no-fund
npm run lint
npm test -- --ci
npm run build
