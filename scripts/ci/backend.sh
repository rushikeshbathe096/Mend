#!/usr/bin/env bash
# CI: backend unit + integration tests (needs PostgreSQL and Redis; see README "CI").
# PostgreSQL: MEND_TEST_DB_SERVER_URL (default jdbc:postgresql://localhost:5433/postgres, the docker-compose server);
# MEND_TEST_DB_USER / MEND_TEST_DB_PASSWORD. Redis: REDIS_HOST / REDIS_PORT (default localhost:6379).
# An exported JWT_SECRET (CI generates one per run) overrides the test profile's jwt.secret in Spring test contexts; no
# test depends on the value (JwtSecret*Test build their own environment without OS variables).
set -euo pipefail
cd "$(dirname "$0")/../../backend"
./mvnw -B -ntp clean test
