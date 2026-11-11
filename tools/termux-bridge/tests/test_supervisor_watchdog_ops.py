"""Host-isolated regression tests for the Termux-local #152 recovery helpers.

Run with: python -m pytest tools/termux-bridge/tests/test_supervisor_watchdog_ops.py -q
All tests use a disposable HOME and never signal a production daemon.
"""
import fcntl
import json
import os
import shutil
import subprocess
import sys
import time
from pathlib import Path

OPS = Path(__file__).resolve().parents[1] / "ops"
WATCH_NAME = "supervisor-watchdog-job.py"
CONTROL_NAME = "supervisor-watchdog-control.py"


def fixture_env(tmp_path):
    home = tmp_path
    root = home / ".closepaw"
    root.mkdir()
    (root / "supervisor.lock").write_text("")
    (root / "bridge.py").write_text("# fixture; not a bridge\n")
    hook = home / ".termux" / "boot" / "10-closepaw-bridge"
    hook.parent.mkdir(parents=True)
    hook.write_text('#!/bin/sh\nprintf x >> "$HOME/boot-invocations"\n')
    hook.chmod(0o700)
    for name in (WATCH_NAME, CONTROL_NAME):
        shutil.copyfile(OPS / name, root / name)
        (root / name).chmod(0o700)
    fakebin = home / "bin"
    fakebin.mkdir()
    scheduler = fakebin / "termux-job-scheduler"
    scheduler.write_text(
        "#!" + sys.executable + "\n"
        "import os,sys\n"
        "from pathlib import Path\n"
        "f=Path(os.environ['HOME'])/'.scheduler-pending'\n"
        "a=sys.argv[1:]\n"
        "if '--pending' in a:\n"
        " print('Pending Job 152218: fixture' if f.exists() else 'No pending jobs')\n"
        "elif '--cancel' in a:\n"
        " f.unlink(missing_ok=True)\n"
        "else:\n"
        " f.write_text('152218')\n"
    )
    scheduler.chmod(0o700)
    env = dict(os.environ, HOME=str(home), PATH=str(fakebin) + os.pathsep + os.environ["PATH"])
    return root, env


def invoke(path, env):
    return subprocess.run(
        [sys.executable, str(path)],
        env=env, capture_output=True, text=True, timeout=10, check=False
    )


def test_watchdog_locked_supervisor_is_noop_and_unverified_rearm_refused(tmp_path):
    root, env = fixture_env(tmp_path)
    state = root / "supervisor-watchdog-attempts.json"
    state.write_text(json.dumps({"attempts": 2, "last_try_epoch": 1}))
    fd = os.open(root / "supervisor.lock", os.O_RDONLY)
    try:
        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        assert invoke(root / WATCH_NAME, env).returncode == 0
        assert json.loads(state.read_text())["attempts"] == 2
        assert not (tmp_path / "boot-invocations").exists()
    finally:
        fcntl.flock(fd, fcntl.LOCK_UN)
        os.close(fd)


def test_watchdog_bounded_three_attempts_and_disabled_marker(tmp_path):
    root, env = fixture_env(tmp_path)
    watcher = root / WATCH_NAME
    for _ in range(4):
        assert invoke(watcher, env).returncode == 0
    state = json.loads((root / "supervisor-watchdog-attempts.json").read_text())
    assert state["attempts"] == 3
    # Child hook is launched asynchronously, but only the first three invocations run it.
    for _ in range(30):
        if (tmp_path / "boot-invocations").exists() and len((tmp_path / "boot-invocations").read_text()) == 3:
            break
        time.sleep(0.05)
    assert (tmp_path / "boot-invocations").read_text() == "xxx"
    (root / "supervisor-restart.disabled").write_text("manual stop\n")
    (root / "supervisor-watchdog-attempts.json").unlink()
    assert invoke(watcher, env).returncode == 0
    assert not (root / "supervisor-watchdog-attempts.json").exists()
    assert (tmp_path / "boot-invocations").read_text() == "xxx"


def test_control_explicit_stop_cancel_and_authorized_enable(tmp_path):
    root, env = fixture_env(tmp_path)
    ctl = root / CONTROL_NAME
    enabled = subprocess.run([sys.executable, str(ctl), "enable"], env=env,
                             capture_output=True, text=True, timeout=10)
    assert enabled.returncode == 0
    assert (tmp_path / ".scheduler-pending").exists()
    paused = subprocess.run([sys.executable, str(ctl), "disable"], env=env,
                            capture_output=True, text=True, timeout=10)
    assert paused.returncode == 0
    assert (root / "supervisor-restart.disabled").read_text() == "closepaw-owner-stopped-v1\n"
    assert not (tmp_path / ".scheduler-pending").exists()
    assert invoke(root / WATCH_NAME, env).returncode == 0
    assert not (tmp_path / "boot-invocations").exists()
    restarted = subprocess.run([sys.executable, str(ctl), "enable"], env=env,
                               capture_output=True, text=True, timeout=10)
    assert restarted.returncode == 0
    assert (tmp_path / ".scheduler-pending").exists()
    assert not (root / "supervisor-restart.disabled").exists()


def test_unknown_stop_marker_is_not_overwritten(tmp_path):
    root, env = fixture_env(tmp_path)
    marker = root / "supervisor-restart.disabled"
    marker.write_text("other owner intentionally stopped\n")
    res = subprocess.run([sys.executable, str(root / CONTROL_NAME), "enable"],
                         env=env, capture_output=True, text=True, timeout=10)
    assert res.returncode != 0
    assert marker.read_text() == "other owner intentionally stopped\n"


def test_stop_waits_for_inflight_watchdog_dispatch(tmp_path):
    """A Stop acknowledgment must wait for the shared dispatch gate."""
    root, env = fixture_env(tmp_path)
    ctl = root / CONTROL_NAME
    assert subprocess.run([sys.executable, str(ctl), "enable"], env=env,
                          capture_output=True, text=True, timeout=10).returncode == 0
    guard = root / "supervisor-watchdog-dispatch.lock"
    fd = os.open(guard, os.O_RDWR | os.O_CREAT, 0o600)
    fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
    process = subprocess.Popen(
        [sys.executable, str(ctl), "disable"], env=env,
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True
    )
    try:
        for _ in range(40):
            if (root / "supervisor-restart.disabled").exists():
                break
            time.sleep(0.025)
        assert (root / "supervisor-restart.disabled").exists()
        assert process.poll() is None  # Stop has not falsely acknowledged yet.
    finally:
        fcntl.flock(fd, fcntl.LOCK_UN)
        os.close(fd)
    out, err = process.communicate(timeout=10)
    assert process.returncode == 0, (out, err)
    assert not (tmp_path / ".scheduler-pending").exists()
