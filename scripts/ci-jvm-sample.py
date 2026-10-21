#!/usr/bin/env python3
"""Sample aggregate Java RSS and host memory only; no argv/env/process content."""
import json
import pathlib
import signal
import sys
import time

running = True

def stop(*_):
    global running
    running = False

signal.signal(signal.SIGTERM, stop)
signal.signal(signal.SIGINT, stop)
peak_rss_kib = peak_count = samples = 0
minimum_available_kib = None
while running:
    rss = count = 0
    for directory in pathlib.Path('/proc').glob('[0-9]*'):
        try:
            if directory.joinpath('comm').read_text().strip() != 'java':
                continue
            fields = dict(line.split(':', 1) for line in directory.joinpath('status').read_text().splitlines() if ':' in line)
            rss += int(fields.get('VmRSS', '0 kB').split()[0])
            count += 1
        except (OSError, ValueError):
            continue
    try:
        available = int(next(line.split()[1] for line in pathlib.Path('/proc/meminfo').read_text().splitlines() if line.startswith('MemAvailable:')))
        minimum_available_kib = available if minimum_available_kib is None else min(minimum_available_kib, available)
    except (OSError, StopIteration, ValueError):
        pass
    samples += 1
    peak_rss_kib = max(peak_rss_kib, rss)
    peak_count = max(peak_count, count)
    time.sleep(1)
pathlib.Path(sys.argv[1]).write_text(json.dumps(dict(samples=samples,
    peak_aggregate_java_rss_kib=peak_rss_kib, peak_java_process_count=peak_count,
    minimum_host_available_kib=minimum_available_kib,
    limitation='Sampled aggregate RSS includes shared pages; not unique physical RSS or R8-only memory.'), indent=2))
