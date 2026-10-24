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
exit $code
