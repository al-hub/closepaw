#!/usr/bin/env python3
"""Generate the same standalone daemon that Gradle packages for Termux."""
import argparse
from pathlib import Path


def bundle(root):
    sources = root / 'tools' / 'termux-bridge'
    cold = (sources / 'closepaw_cold_archive.py').read_text()
    jobs = (sources / 'closepaw_jobs.py').read_text().replace(
        'from closepaw_cold_archive import ColdArchive, ColdArchiveError\n', '')
    return cold + '\n' + jobs + '\n' + (sources / 'closepaw_maintenance.py').read_text() + '\n' + (sources / 'closepaw_bridge.py').read_text().replace(
        'from closepaw_jobs import JobManager, JobError\n', '').replace(
        'from closepaw_maintenance import MaintenanceController, MaintenanceError, approve_pin\n', '')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('output', type=Path)
    options = parser.parse_args()
    options.output.parent.mkdir(parents=True, exist_ok=True)
    options.output.write_text(bundle(Path(__file__).resolve().parents[1]))
