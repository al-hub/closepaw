#!/usr/bin/env bash
# Local profile only. Never enables remote build scans or captures environment.
set -euo pipefail
label="${1:?usage: ci-gradle.sh <label> <tasks...>}"
shift
mkdir -p build/ci-metrics
printf 'cpus=%s\n' "$(getconf _NPROCESSORS_ONLN)" > "build/ci-metrics/$label-host.txt"
free -m >> "build/ci-metrics/$label-host.txt"
start="$(date +%s)"
set +e
/usr/bin/time -v -o "build/ci-metrics/$label-process.txt" \
  ./gradlew "$@" --profile --console=plain --stacktrace 2>&1 | tee "build/ci-metrics/$label-gradle.log"
result=${PIPESTATUS[0]}
set -e
printf 'elapsed_seconds=%s\nexit_code=%s\n' "$(( $(date +%s) - start ))" "$result" > "build/ci-metrics/$label-wall.txt"
# Standard Gradle HTML profile gives task durations, including R8, test and lint.
# The log preserves FROM-CACHE/UP-TO-DATE and configuration-cache diagnostics.
exit "$result"
