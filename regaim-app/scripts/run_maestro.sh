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
