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
set +e
"${timer[@]}" ./gradlew "$@" --profile --console=plain --stacktrace 2>&1 | tee "build/ci-metrics/$label-gradle.log"
result=${PIPESTATUS[0]}
set -e
printf 'elapsed_seconds=%s\nexit_code=%s\n' "$(( $(date +%s) - start ))" "$result" > "build/ci-metrics/$label-wall.txt"
# Emit task table to logs as well, so completed-job logs suffice for analysis.
python3 - <<'PROFILE'
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
