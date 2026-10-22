#!/usr/bin/env bash
set -euo pipefail
pkg=com.n8rtz.winetracker
root="$RUNNER_TEMP/replay"
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell run-as "$pkg" mkdir -p files/saved-evidence
adb push "$root/input.json" /data/local/tmp/evidence-input.json
adb shell run-as "$pkg" cp /data/local/tmp/evidence-input.json files/saved-evidence/input.json
expected=$(sha256sum "$root/input.json" | awk '{print $1}')
actual=$(adb shell run-as "$pkg" sha256sum files/saved-evidence/input.json | awk '{print $1}')
test "$expected" = "$actual"
set +e
timeout --signal=TERM 1200 adb shell am instrument -w -r -e savedEvidenceReplay true -e class com.yourapp.wine.textlab.SavedEvidenceReplayTest "$pkg.test/androidx.test.runner.AndroidJUnitRunner" > "$root/instrumentation.txt" 2>&1
result=$?
set -e
adb exec-out run-as "$pkg" tar -cf - -C files/saved-evidence . > "$root/device-results.tar"
tar -xf "$root/device-results.tar" -C "$root"
cat "$root/instrumentation.txt"
test "$result" = 0
grep -q 'OK (1 test)' "$root/instrumentation.txt"
