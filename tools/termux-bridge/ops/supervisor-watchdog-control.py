#!/data/data/com.termux/files/usr/bin/python3
"""ClosePaw #152 explicit owner-authorized auto-recovery Stop/Enable control."""
import fcntl
import hashlib
import json
import os
import stat
import subprocess
import sys
import tempfile
from pathlib import Path

root = Path.home() / ".closepaw"
watcher = root / "supervisor-watchdog-job.py"
watch_sha = "b2efb364cdd4eb18bfcd10c85c7dd9d5a42244d491524836415cd13ef88bdc4a"
marker = root / "supervisor-restart.disabled"
control_lock = root / "supervisor-watchdog-control.lock"
job_id = "152218"
magic = "closepaw-owner-stopped-v1\n"

def regular(path):
    try:
        s = path.lstat()
        return stat.S_ISREG(s.st_mode) and not path.is_symlink()
    except OSError:
        return False

def jobs(*args):
    return subprocess.run(["termux-job-scheduler", *args],
                          capture_output=True, text=True, timeout=15, check=False)

def pending():
    result = jobs("--pending")
    if result.returncode != 0:
        raise RuntimeError("scheduler_unavailable")
    return "Job " + job_id + ":" in result.stdout

def write_stop_marker():
    if marker.exists():
        if not regular(marker) or marker.read_text() != magic:
            raise RuntimeError("unsafe_existing_stop_marker")
    fd, temp = tempfile.mkstemp(prefix=".watchdog-disabled-", dir=root)
    try:
        with os.fdopen(fd, "w") as stream:
            stream.write(magic)
            stream.flush()
            os.fsync(stream.fileno())
        os.chmod(temp, 0o600)
        os.replace(temp, marker)
        dfd = os.open(root, os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(dfd)
        finally:
            os.close(dfd)
    finally:
        if os.path.exists(temp):
            os.unlink(temp)

def main():
    if len(sys.argv) != 2 or sys.argv[1] not in ("status", "disable", "enable"):
        print("usage: supervisor-watchdog-control.py [status|disable|enable]")
        return 2
    choice = sys.argv[1]
    if choice != "status" and (not regular(watcher) or
        hashlib.sha256(watcher.read_bytes()).hexdigest() != watch_sha):
        print("BLOCKED_unexpected_watcher_source")
        return 3
    if choice == "status":
        print("WATCHDOG_DISABLED=", marker.exists())
        print("PERIODIC_REGISTERED=", pending())
        return 0
    if control_lock.is_symlink():
        print("BLOCKED_unsafe_control_lock")
        return 3
    fd = os.open(control_lock, os.O_WRONLY | os.O_CREAT | os.O_NOFOLLOW, 0o600)
    try:
        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        os.close(fd)
        print("BLOCKED_control_busy")
        return 3
    # Durable Stop marker precedes the serialized dispatch gate. Wait until any
    # already-running watcher finishes before reporting Stop as confirmed.
    try:
        if choice == "disable":
            write_stop_marker()
        dispatch_lock = root / "supervisor-watchdog-dispatch.lock"
        if dispatch_lock.is_symlink():
            raise RuntimeError("unsafe_dispatch_lock")
        guard_fd = os.open(dispatch_lock, os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
        fcntl.flock(guard_fd, fcntl.LOCK_EX)
    except (OSError, RuntimeError):
        fcntl.flock(fd, fcntl.LOCK_UN)
        os.close(fd)
        print("BLOCKED_dispatch_gate")
        return 4
    try:
        if choice == "disable":
            res = jobs("--cancel", "--job-id", job_id)
            if res.returncode or pending():
                print("BLOCKED_cancel_unconfirmed_marker_remains")
                return 4
            print("AUTO_RECOVERY_DISABLED_verified")
            return 0
        if (root / "maintenance-transaction.json").exists():
            print("BLOCKED_unresolved_maintenance_transaction")
            return 4
        if marker.exists():
            if not regular(marker) or marker.read_text() != magic:
                print("BLOCKED_unknown_user_stop_marker")
                return 4
        res = jobs("--script", str(watcher), "--job-id", job_id,
                   "--period-ms", "900000", "--network", "none",
                   "--battery-not-low", "true", "--persisted", "true")
        if res.returncode or not pending():
            print("BLOCKED_registration_unconfirmed_marker_preserved")
            return 4
        if marker.exists():
            marker.unlink()  # Explicit owner-approved reenable only.
            dfd = os.open(root, os.O_DIRECTORY | os.O_RDONLY)
            try:
                os.fsync(dfd)
            finally:
                os.close(dfd)
        print("AUTO_RECOVERY_ENABLED_scheduler_registered")
        return 0
    finally:
        fcntl.flock(guard_fd, fcntl.LOCK_UN)
        os.close(guard_fd)
        fcntl.flock(fd, fcntl.LOCK_UN)
        os.close(fd)

try:
    sys.exit(main())
except (OSError, RuntimeError, ValueError, KeyError, TypeError, subprocess.TimeoutExpired):
    print("BLOCKED_control_failed_check_marker_and_pending")
    sys.exit(4)
