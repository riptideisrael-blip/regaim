#!/usr/bin/env bash
# Installs the QA app on the running emulator and runs the Maestro smoke tests (from the repository root).
# Called by .github/workflows/qa.yml inside the emulator runner. The tests' result is this script's result.
set -u
APK=regaim-app/app/build/outputs/apk/qa/app-qa.apk
adb wait-for-device
# A fresh emulator is slow: wait until it has fully started, and keep "X isn't responding" windows from covering the app.
for i in $(seq 1 60); do [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break; sleep 2; done
adb shell settings put global hide_error_dialogs 1 || true
adb shell settings put global anr_show_background 0 || true
sleep 15
adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1 || true
adb install -r -g "$APK" || { echo "install failed"; exit 1; }
# The emulator in English (the tests use the English texts).
adb shell "settings put system system_locales en-US" || true
mkdir -p maestro-out
maestro test regaim-app/.maestro --format junit --output maestro-report.xml --test-output-dir maestro-out
code=$?
adb logcat -d > maestro-out/logcat.txt 2>/dev/null || true
# Crashes of the app, and what a fresh launch shows (readable on the run without downloading the logs).
grep -A12 'FATAL EXCEPTION' maestro-out/logcat.txt | grep -A12 'com.regaim.app' | head -40 | sed 's/^/::error title=App crash::/' || true
adb shell pm clear com.regaim.app.qa >/dev/null 2>&1 || true
adb shell monkey -p com.regaim.app.qa -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
sleep 8
python3 - <<'PY3' || true
import re, subprocess, time
def dump(step):
    subprocess.run("adb shell uiautomator dump /sdcard/ui.xml", shell=True, capture_output=True)
    x = subprocess.run("adb shell cat /sdcard/ui.xml", shell=True, capture_output=True, text=True).stdout
    nodes = re.findall(r'<node [^>]*>', x)
    texts = []
    for n in nodes:
        t = (re.search(r' text="([^"]*)"', n) or [None, ""])[1] or (re.search(r'content-desc="([^"]*)"', n) or [None, ""])[1]
        if t.strip(): texts.append(t)
    pkg = sorted(set(re.findall(r'package="([^"]+)"', x)))
    print(f"::notice title=Screen {step}::{' | '.join(pkg)} :: {' | '.join(texts[:40])}")
    return nodes
def tap(nodes, label):
    for n in nodes:
        t = (re.search(r' text="([^"]*)"', n) or [None, ""])[1]
        if t.strip() == label:
            b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
            if b:
                x1, y1, x2, y2 = map(int, b.groups())
                subprocess.run(f"adb shell input tap {(x1+x2)//2} {(y1+y2)//2}", shell=True)
                return True
    return False
nodes = dump("1 start")
for i, label in enumerate(["Skip", "Continue without an account"], start=2):
    ok = tap(nodes, label)
    time.sleep(5)
    nodes = dump(f"{i} after {label} ({'tapped' if ok else 'not found'})")
PY3
adb logcat -d -t 400 | grep -E 'AndroidRuntime|regaim' | tail -15 | sed 's/^/::notice title=Log::/' || true
# Each flow's result as a note on the run (readable without downloading the logs).
python3 - <<'PY2' || true
import xml.etree.ElementTree as ET
try:
    r = ET.parse("maestro-report.xml").getroot()
except Exception as e:
    print(f"::error title=Maestro::no report ({e})"); raise SystemExit
for tc in r.iter("testcase"):
    bad = tc.find("failure") if tc.find("failure") is not None else tc.find("error")
    name = tc.get("name")
    if bad is None:
        print(f"::notice title=Maestro passed::{name}")
    else:
        msg = ((bad.get("message") or "") + " " + (bad.text or "")).strip().replace("\n", " ")[:400]
        print(f"::error title=Maestro failed::{name}: {msg}")
PY2
exit $code
