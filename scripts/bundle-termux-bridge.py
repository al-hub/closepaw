#!/usr/bin/env python3
"""Generate the same standalone daemon that Gradle packages for Termux."""
import argparse
from pathlib import Path


def bundle(root):
    sources = root / 'tools' / 'termux-bridge'
    return (sources / 'closepaw_jobs.py').read_text() + '\n' + (sources / 'closepaw_bridge.py').read_text().replace(
        'from closepaw_jobs import JobManager, JobError\n', '')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('output', type=Path)
    options = parser.parse_args()
    options.output.parent.mkdir(parents=True, exist_ok=True)
    options.output.write_text(bundle(Path(__file__).resolve().parents[1]))
