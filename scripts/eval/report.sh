#!/usr/bin/env bash
# M4 U4.4: metrics report from one snapshot directory (files only; never a database). ADR-004 §6.
# Usage: scripts/eval/report.sh --snapshot DIR --out DIR [--seed N] [--resamples N] [--cost-per-contact-minor N]
#                               [--planned-total N] [--allow-invalid] [--title TEXT]
# Writes <out>/<snapshot-id>.md and .json. Builds the backend jar first if it is missing (or with REBUILD=1).
# Runs com.mend.evaluation.cli.ReportCli from the jar via Spring Boot's PropertiesLauncher: no new plugin or dependency.
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
jar="$root/backend/target/backend-0.0.1-SNAPSHOT.jar"
if [[ ! -f "$jar" || "${REBUILD:-0}" == "1" ]]; then
  (cd "$root/backend" && ./mvnw -q -B -ntp -DskipTests package)
fi
exec java -cp "$jar" -Dloader.main=com.mend.evaluation.cli.ReportCli \
  org.springframework.boot.loader.launch.PropertiesLauncher "$@"
