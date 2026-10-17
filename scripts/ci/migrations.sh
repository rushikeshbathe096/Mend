#!/usr/bin/env bash
# CI: Flyway migrations on a brand-new empty PostgreSQL database: migrate V1..latest, then validate
# (backend FreshDatabaseMigrationTest; creates and drops its own mend_test_* database on MEND_TEST_DB_SERVER_URL).
set -euo pipefail
cd "$(dirname "$0")/../../backend"
./mvnw -B -ntp test -Dtest=FreshDatabaseMigrationTest -Dsurefire.failIfNoSpecifiedTests=true
