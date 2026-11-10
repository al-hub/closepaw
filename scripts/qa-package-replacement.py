#!/usr/bin/env python3
"""Real APK replacement on an isolated emulator; no Activity or AI session launch.

Unchanged production Receiver/FGS/local MCP only. The x86 build has no native
tunnel runtime. External reachability/client sessions are deliberately untested.
No production release is published or installed by this script.
"""
import json
from pathlib import Path
import subprocess
import time
import urllib.request

PACKAGE = "ai.closepaw"
APP = "app/build/outputs/apk/debug/app-debug.apk"
TEST = "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
FIXTURE = "ai.closepaw.chatgpt.PackageReplacementFixtureTest"
OUT = Path("build/package-replacement/evidence.json")
evidence = {"scope": "isolated_emulator_receiver_fgs_local_mcp_only", "events": []}


def adb(*args, timeout=60):
    result = subprocess.run(["adb", *args], capture_output=True, text=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError("adb command failed: " + args[0])
    return result.stdout.strip()


def fixture(method):
    result = adb("shell", "am", "instrument", "-w", "-e", "class", FIXTURE + "#" + method,
                 PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner")
    if "OK (1 test)" not in result:
        raise RuntimeError("fixture failed: " + method + "\n" + result[-3000:])


def healthy(url):
    try:
        with urllib.request.urlopen(url, timeout=1) as response:
            return response.status == 200 and json.load(response) == {"status": "ok"}
    except (OSError, ValueError):
        return False


def cold_replace():
    # am kill avoids the stopped-package flag that force-stop would set.
    adb("shell", "am", "kill", PACKAGE)
    if healthy(url):
        raise RuntimeError("service still running before cold replacement")
    start = time.monotonic()
    if "Success" not in adb("install", "-r", APP):
        raise RuntimeError("package replacement failed")
    return start


try:
    if adb("shell", "getprop", "ro.kernel.qemu") != "1":
        raise RuntimeError("isolated emulator required")
    for apk in (APP, TEST):
        if "Success" not in adb("install", "-r", apk):
            raise RuntimeError("fixture install failed")
    port = adb("forward", "tcp:0", "tcp:18424")
    url = "http://127.0.0.1:" + port + "/health"
    fixture("seedEnabled")
    start = cold_replace()
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
    start = cold_replace()
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
    try:
        logs = adb("logcat", "-d", "-t", "500", "AndroidRuntime:E", "ActivityManager:I", "*:S")
        evidence["isolated_os_errors"] = [line for line in logs.splitlines()
            if any(word in line for word in ("Exception", "Caused by", "ai.closepaw", "FATAL", "foreground"))][-40:]
    except Exception:
        evidence["isolated_os_errors"] = ["unavailable"]
    evidence["reason"] = str(error)
    raise
finally:
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(evidence, indent=2) + "\n")
    print(json.dumps(evidence))
