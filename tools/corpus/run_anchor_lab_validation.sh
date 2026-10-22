#!/usr/bin/env bash
set -euo pipefail
pkg=com.n8rtz.winetracker
root="$RUNNER_TEMP/replay"
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
set +e
timeout --signal=TERM 300 adb shell am instrument -w -r -e anchorLabValidation true -e class com.yourapp.wine.textlab.AnchorLabValidationTest "$pkg.test/androidx.test.runner.AndroidJUnitRunner" > "$root/instrumentation.txt" 2>&1
status=$?
set -e
cat "$root/instrumentation.txt"
test "$status" = 0
grep -q 'OK (1 test)' "$root/instrumentation.txt"
