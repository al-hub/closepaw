#!/data/data/com.termux/files/usr/bin/python3
"""ClosePaw #152: fail-closed, rate-bounded supervisor reentry on Android JobScheduler."""
import fcntl
import json
import os
import stat
import subprocess
import sys
import tempfile
import time
from pathlib import Path

root = Path.home() / ".closepaw"
hook = Path.home() / ".termux/boot/10-closepaw-bridge"
lock = root / "supervisor.lock"
state_path = root / "supervisor-watchdog-attempts.json"

def regular_file(path):
    try:
        info = path.lstat()
        return stat.S_ISREG(info.st_mode) and not path.is_symlink()
    except OSError:
        return False


def rearm_only_after_authenticated_health():
    """Reset bounded attempts only with real supervisor provenance and authenticated jobs."""
    import urllib.request
    import hashlib
    if not regular_file(state_path) or state_path.stat().st_size > 1024:
        return
    try:
        state = json.loads(state_path.read_text())
        count = state.get("attempts")
        last = state.get("last_try_epoch", 0)
        if type(count) is not int or not 1 <= count <= 3:
            return
        if type(last) is not int or last > int(time.time()):
            return
        if (root / "supervisor-restart.disabled").exists():
            return
        token_file = root / "token"
        pidfile = root / "bridge.pid"
        if not regular_file(token_file) or not regular_file(pidfile):
            return
        token = token_file.read_text().strip()
        if len(token) < 32 or "\n" in token or "\r" in token:
            return
        pidinfo = json.loads(pidfile.read_text())
        child_pid = pidinfo.get("pid")
        if (pidinfo.get("identity") != "closepaw-bridge" or
            pidinfo.get("port") != 18422 or
            type(child_pid) is not int or child_pid <= 1):
            return
        def proc(pid):
            base = Path("/proc") / str(pid)
            parts = (base / "stat").read_text().split(") ", 1)[1].split()
            return int(parts[1]), (base / "cmdline").read_bytes()
        parent_pid, child_cmd = proc(child_pid)
        if b"bridge.py" not in child_cmd or b"--supervise" in child_cmd:
            return  # Adopted daemon can legitimately have PPID 1 after supervisor restart.
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        with opener.open("http://127.0.0.1:18422/v1/health", timeout=2) as response:
            health = json.loads(response.read(4096))
        if (health.get("status") != "ok" or
            health.get("identity") != "closepaw-bridge" or
            health.get("job_api_version") != 1):
            return
        request = urllib.request.Request("http://127.0.0.1:18422/v1/jobs",
                  headers={"X-ClosePaw-Token": token})
        with opener.open(request, timeout=2) as response:
            jobs = json.loads(response.read(4096))
        if jobs.get("status") != "ready" or jobs.get("job_api_version") != 1:
            return
        maintenance_request = urllib.request.Request(
            "http://127.0.0.1:18427/v1/maintenance/status",
            headers={"X-ClosePaw-Token": token})
        with opener.open(maintenance_request, timeout=2) as response:
            maintenance = json.loads(response.read(4096))
        if (maintenance.get("supervisor") != "running" or
            maintenance.get("daemon_state") != "responding" or
            maintenance.get("daemon_source_sha256") != hashlib.sha256((root / "bridge.py").read_bytes()).hexdigest() or
            maintenance.get("runtime_revision") != health.get("runtime_revision")):
            return
        if (root / "supervisor-restart.disabled").exists() or (root / "maintenance-transaction.json").exists():
            return
        fd, temp = tempfile.mkstemp(prefix=".watchdog-rearm-", dir=root)
        try:
            with os.fdopen(fd, "w") as out:
                json.dump({"attempts": 0, "last_try_epoch": last,
                           "last_verified_epoch": int(time.time())}, out)
                out.flush()
                os.fsync(out.fileno())
            os.chmod(temp, 0o600)
            os.replace(temp, state_path)
            dfd = os.open(root, os.O_RDONLY | os.O_DIRECTORY)
            try:
                os.fsync(dfd)
            finally:
                os.close(dfd)
        finally:
            if os.path.exists(temp):
                os.unlink(temp)
    except (OSError, ValueError, KeyError, TypeError, IndexError,
            AttributeError, TimeoutError, UnicodeError):
        return

dispatch_lock = root / "supervisor-watchdog-dispatch.lock"
try:
    if dispatch_lock.is_symlink():
        sys.exit(0)
    dispatch_fd = os.open(dispatch_lock, os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
    fcntl.flock(dispatch_fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
except (BlockingIOError, OSError):
    sys.exit(0)  # Another watchdog/Stop owns the one-at-a-time dispatch gate.

if (root / "supervisor-restart.disabled").exists():
    sys.exit(0)
if (root / "maintenance-transaction.json").exists():
    sys.exit(0)
if not (regular_file(hook) and os.access(hook, os.X_OK) and
        regular_file(root / "bridge.py") and regular_file(lock)):
    sys.exit(0)

try:
    fd = os.open(lock, os.O_RDONLY | os.O_NOFOLLOW)
    try:
        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        fcntl.flock(fd, fcntl.LOCK_UN)
    except BlockingIOError:
        rearm_only_after_authenticated_health()
        sys.exit(0)  # Healthy-only verified rearm, otherwise fail closed.
    finally:
        os.close(fd)
except OSError:
    sys.exit(0)  # Unknown lock state must not authorize launch.

if state_path.exists():
    if not regular_file(state_path) or state_path.stat().st_size > 1024:
        sys.exit(0)
    try:
        state = json.loads(state_path.read_text())
        count = state["attempts"]
        if type(count) is not int or not 0 <= count <= 3:
            sys.exit(0)
    except (OSError, ValueError, KeyError, TypeError):
        sys.exit(0)
else:
    count = 0
if count >= 3:
    sys.exit(0)  # Require an explicit local reset after three unsuccessful restarts.

fd, temp = tempfile.mkstemp(prefix=".watchdog-", dir=root)
try:
    with os.fdopen(fd, "w") as out:
        json.dump({"attempts": count + 1, "last_try_epoch": int(time.time())}, out)
        out.flush()
        os.fsync(out.fileno())
    os.chmod(temp, 0o600)
    os.replace(temp, state_path)
    dfd = os.open(root, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(dfd)
    finally:
        os.close(dfd)
finally:
    if os.path.exists(temp):
        os.unlink(temp)

try:
    subprocess.Popen([str(hook)], stdin=subprocess.DEVNULL,
                     stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                     start_new_session=True, close_fds=True)
except OSError:
    sys.exit(1)
