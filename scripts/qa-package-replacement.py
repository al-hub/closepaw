#!/usr/bin/env python3
"""Real APK replacement on an isolated emulator; no AI session launch.

An Activity is opened during pre-update setup to model an already-used app.
Post-update Activity launch is diagnostic rescue after FAIL only, never a PASS.

Unchanged production Receiver/FGS/local MCP only. The x86 build has no native
tunnel runtime. External reachability/client sessions are deliberately untested.
No production release is published or installed by this script.
"""
import json
import hashlib
import os
from pathlib import Path
import subprocess
import time
import urllib.request

os.chdir(Path(__file__).resolve().parents[1])
PACKAGE = "ai.closepaw"
APP = None
FIXTURE = "ai.closepaw.chatgpt.PackageReplacementFixtureTest"
OUT = Path("build/package-replacement/evidence.json")
evidence = {"scope": "isolated_emulator_receiver_fgs_local_mcp_only", "events": []}


def adb(*args, timeout=60):
    result = subprocess.run(["adb", *args], capture_output=True, text=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError("adb command failed: " + args[0] + "\n" + (result.stdout + result.stderr)[-3000:])
    return result.stdout.strip()


def fixture(method):
    result = adb("shell", "am", "instrument", "-w", "-e", "class", FIXTURE + "#" + method,
                 PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner")
    if "OK (1 test)" not in result:
        raise RuntimeError("fixture failed: " + method + "\n" + result[-3000:])
    return result


def healthy(url):
    try:
        with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(url, timeout=1) as response:
            return response.status == 200 and json.load(response) == {"status": "ok"}
    except (OSError, ValueError):
        return False


def installed_apk():
    # AGP installs its exact selected variant first. Ask Android for the installed
    # base APK instead of assuming a Gradle output path on the runner.
    paths = adb("shell", "pm", "path", PACKAGE).splitlines()
    if len(paths) != 1 or not paths[0].startswith("package:/data/app/"):
        raise RuntimeError("expected one installed fixture base APK")
    remote = paths[0].removeprefix("package:")
    if not remote.endswith(".apk"):
        raise RuntimeError("invalid installed APK path")
    staged = Path("build/package-replacement-input/base.apk").resolve()
    staged.parent.mkdir(parents=True, exist_ok=True)
    adb("pull", remote, str(staged))
    evidence["fixture_apk_bytes"] = staged.stat().st_size
    evidence["fixture_apk_sha256"] = hashlib.sha256(staged.read_bytes()).hexdigest()
    return str(staged)


def diagnostics(stage):
    observations = {}
    for name, args in {
        "package": ("dumpsys", "package", PACKAGE),
        "services": ("dumpsys", "activity", "services", PACKAGE),
        "broadcasts": ("dumpsys", "activity", "broadcasts"),
        "activities": ("dumpsys", "activity", "activities"),
    }.items():
        try:
            value = adb("shell", *args)
            observations[name] = [line.strip() for line in value.splitlines() if any(
                key in line for key in (PACKAGE, "stopped=", "notLaunched=", "isForeground=", "startRequested=",
                                       "MY_PACKAGE_REPLACED", "app=null", "app=ProcessRecord", "topResumedActivity", "mResumedActivity"))][-60:]
        except Exception:
            observations[name] = ["unavailable"]
    evidence[stage] = observations


def main_pid():
    result = subprocess.run(["adb", "shell", "pidof", PACKAGE], capture_output=True, text=True, timeout=10)
    return result.stdout.strip()


def wait_healthy(seconds):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline and not healthy(url):
        time.sleep(0.5)
    return healthy(url)


def replace(cold=True):
    if cold:
        # am kill avoids the stopped-package flag that force-stop would set.
        adb("shell", "am", "kill", PACKAGE)
        if healthy(url):
            raise RuntimeError("cannot prepare cold fixture: service still running")
    diagnostics("before_replace_" + str(len(evidence["events"])))
    start = time.monotonic()
    if "Success" not in adb("install", "-r", "-t", APP):
        raise RuntimeError("package replacement failed")
    installed_at = time.monotonic()
    evidence["events"].append({"case": "fixture_package_install",
                               "duration_ms": round((installed_at - start) * 1000)})
    return installed_at


try:
    if adb("shell", "getprop", "ro.kernel.qemu") != "1":
        raise RuntimeError("isolated emulator required")
    evidence["android_api"] = adb("shell", "getprop", "ro.build.version.sdk")
    APP = installed_apk()
    port = adb("forward", "tcp:0", "tcp:18424")
    url = "http://127.0.0.1:" + port + "/health"
    fixture("seedEnabled")
    # A real updated app has already been launched and connected before update.
    # This launch is BEFORE replacement, never part of unattended recovery proof.
    evidence["pre_update_launch"] = adb("shell", "am", "start", "-W", "-n", PACKAGE + "/.app.MainActivity")
    if not wait_healthy(25):
        raise RuntimeError("pre-update baseline MCP was not ready")
    adb("shell", "input", "keyevent", "KEYCODE_HOME")
    before_pid = main_pid()
    start = replace(cold=False)
    restored = wait_healthy(45)
    after_pid = main_pid()
    evidence["events"].append({"case": "active_package_replacement", "local_mcp_ready": restored,
                               "process_changed": bool(before_pid and after_pid and before_pid != after_pid),
                               "elapsed_ms": round((time.monotonic() - start) * 1000)})
    if not restored or not before_pid or not after_pid or before_pid == after_pid:
        raise RuntimeError("active package replacement did not establish new-process MCP recovery")
    fixture("seedEnabledForCold")
    start = replace()
    while time.monotonic() < start + 45 and not healthy(url):
        time.sleep(0.5)
    restored = healthy(url)
    evidence["events"].append({"case": "enabled_cold_package_replacement", "local_mcp_ready": restored,
                               "elapsed_ms": round((time.monotonic() - start) * 1000)})
    if not restored:
        raise RuntimeError("unchanged Receiver did not restore local MCP after replacement")
    fixture("seedStopped")
    deadline = time.monotonic() + 10
    while time.monotonic() < deadline and healthy(url):
        time.sleep(0.2)
    start = replace()
    unwanted = False
    while time.monotonic() < start + 45:
        unwanted |= healthy(url)
        time.sleep(0.5)
    evidence["events"].append({"case": "stopped_package_replacement", "unexpected_local_mcp": unwanted})
    if unwanted:
        raise RuntimeError("Stop intent was not preserved")
    fixture("verifyPreservedAndCleanup")
    evidence["synthetic_config_and_marker_preserved"] = True
    evidence["status"] = "PASS"
except Exception as error:
    evidence["status"] = "FAIL"
    diagnostics("failure_before_any_rescue")
    if "url" in globals() and not healthy(url):
        try:
            evidence["diagnostic_manual_launch_result"] = adb("shell", "am", "start", "-W", "-n", PACKAGE + "/.app.MainActivity")
            evidence["diagnostic_manual_launch_recovered"] = wait_healthy(20)
        except Exception:
            evidence["diagnostic_manual_launch_recovered"] = False
    try:
        logs = adb("logcat", "-d", "-t", "5000", "AndroidRuntime:E", "ActivityManager:I", "ActivityTaskManager:I", "BroadcastQueue:V", "*:S")
        evidence["isolated_os_errors"] = [line for line in logs.splitlines()
            if any(word in line for word in ("Exception", "Caused by", "ai.closepaw", "FATAL", "foreground"))][-80:]
    except Exception:
        evidence["isolated_os_errors"] = ["unavailable"]
    try:
        evidence["configuration_diagnostic"] = fixture("inspectConfiguration")[-1500:]
    except Exception as inspection_error:
        evidence["configuration_diagnostic"] = str(inspection_error)[-1500:]
    evidence["reason"] = str(error)
    raise
finally:
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(evidence, indent=2) + "\n")
    print(json.dumps(evidence))
