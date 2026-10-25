#!/usr/bin/env bash
# Installs the QA app on the running emulator and runs the Maestro smoke tests (from the repository root).
# Called by .github/workflows/qa.yml inside the emulator runner. The tests' result is this script's result.
set -u
APK=regaim-app/app/build/outputs/apk/qa/app-qa.apk
adb wait-for-device
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
adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 && adb pull /sdcard/ui.xml maestro-out/first-screen.xml >/dev/null 2>&1
python3 - <<'PY3' || true
import re
try:
    x = open("maestro-out/first-screen.xml", encoding="utf-8").read()
    texts = [t for t in re.findall(r'(?:text|content-desc)="([^"]+)"', x) if t.strip()]
    pkg = set(re.findall(r'package="([^"]+)"', x))
    print(f"::notice title=First screen::{' | '.join(sorted(pkg))} :: {' | '.join(texts[:40])}")
except Exception as e:
    print(f"::notice title=First screen::no dump ({e})")
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
