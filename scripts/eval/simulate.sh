#!/usr/bin/env bash
# M4 U4.5: replay simulator (SYNTHETIC, in memory, never a database): production policy classes + seeded outcome model.
# Usage: scripts/eval/simulate.sh --out DIR [--snapshots DIR] [--seed N] [--size N] [--holdout-bp N]
#                                 [--horizon-days N] [--resamples N]
# Writes the simulated snapshot, its metrics report and the policy comparison. Builds the backend jar first if it is missing (or with REBUILD=1).
# Runs com.mend.evaluation.cli.SimulateCli from the jar via Spring Boot's PropertiesLauncher: no new plugin or dependency.
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
jar="$root/backend/target/backend-0.0.1-SNAPSHOT.jar"
if [[ ! -f "$jar" || "${REBUILD:-0}" == "1" ]]; then
  (cd "$root/backend" && ./mvnw -q -B -ntp -DskipTests package)
fi
exec java -cp "$jar" -Dloader.main=com.mend.evaluation.cli.SimulateCli \
  org.springframework.boot.loader.launch.PropertiesLauncher "$@"
