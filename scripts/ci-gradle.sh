#!/usr/bin/env bash
# Local profile only. Never enables remote build scans or captures environment.
set -euo pipefail
label="${1:?usage: ci-gradle.sh <label> <tasks...>}"
shift
mkdir -p build/ci-metrics
printf 'cpus=%s\n' "$(getconf _NPROCESSORS_ONLN)" > "build/ci-metrics/$label-host.txt"
free -m >> "build/ci-metrics/$label-host.txt"
start="$(date +%s)"
timer=()
if [[ -x /usr/bin/time ]]; then
  timer=(/usr/bin/time -v -o "build/ci-metrics/$label-process.txt")
else
  echo 'GNU time unavailable; wall/task timings still measured' > "build/ci-metrics/$label-process.txt"
fi
gradle_metrics_args=()
sampler_pid=''
if [[ "${CI:-}" == true && "$label" == release ]]; then
  jvm_flags="$(sed -n 's/^org.gradle.jvmargs=//p' gradle.properties)"
  gradle_metrics_args+=("-Dorg.gradle.jvmargs=$jvm_flags -Xlog:gc:file=$PWD/build/ci-metrics/release-gc-%p.log:time,level,tags")
  python3 scripts/ci-jvm-sample.py "build/ci-metrics/$label-jvm-memory.json" &
  sampler_pid=$!
  trap 'if [[ -n "$sampler_pid" ]]; then kill "$sampler_pid" 2>/dev/null || true; fi' EXIT
fi
set +e
"${timer[@]}" ./gradlew "${gradle_metrics_args[@]}" "$@" --profile --console=plain --stacktrace 2>&1 | tee "build/ci-metrics/$label-gradle.log"
result=${PIPESTATUS[0]}
set -e
if [[ -n "$sampler_pid" ]]; then
  kill "$sampler_pid" 2>/dev/null || true
  wait "$sampler_pid" || true
  sampler_pid=''
  if [[ -f "build/ci-metrics/$label-jvm-memory.json" ]]; then
    cat "build/ci-metrics/$label-jvm-memory.json" || true
  else
    echo "JVM sampler did not finish; preserving Gradle exit code $result"
  fi
fi
printf 'elapsed_seconds=%s\nexit_code=%s\n' "$(( $(date +%s) - start ))" "$result" > "build/ci-metrics/$label-wall.txt"
# Emit task table to logs as well, so completed-job logs suffice for analysis.
python3 - <<'PROFILE' || echo 'Gradle profile summary unavailable; original exit code retained'
from html.parser import HTMLParser
from pathlib import Path
class Tasks(HTMLParser):
    def __init__(self):
        super().__init__(); self.row = []; self.cell = None
    def handle_starttag(self, tag, attrs):
        if tag == 'tr': self.row = []
        if tag == 'td': self.cell = ''
    def handle_data(self, data):
        if self.cell is not None: self.cell += data
    def handle_endtag(self, tag):
        if tag == 'td' and self.cell is not None:
            self.row.append(self.cell.strip()); self.cell = None
        if tag == 'tr' and self.row and self.row[0].startswith(':'):
            print('TASK_PROFILE ' + ' | '.join(self.row))
profiles = list(Path('build/reports/profile').glob('profile-*.html'))
if profiles:
    latest = max(profiles, key=lambda p: p.stat().st_mtime)
    Tasks().feed(latest.read_text())
PROFILE
# Standard Gradle HTML profile gives task durations, including R8, test and lint.
# The log preserves FROM-CACHE/UP-TO-DATE and configuration-cache diagnostics.
exit "$result"
