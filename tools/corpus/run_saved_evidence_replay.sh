#!/usr/bin/env bash
set -euo pipefail
pkg=com.n8rtz.winetracker
root="${REPLAY_ROOT:-$RUNNER_TEMP/replay}"
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell run-as "$pkg" mkdir -p files/saved-evidence
adb shell run-as "$pkg" rm -f files/saved-evidence/results.jsonl files/saved-evidence/validation.json files/saved-evidence/progress.json
adb push "$root/input.json" /data/local/tmp/evidence-input.json
adb shell run-as "$pkg" cp /data/local/tmp/evidence-input.json files/saved-evidence/input.json
expected=$(sha256sum "$root/input.json" | awk '{print $1}')
actual=$(adb shell run-as "$pkg" sha256sum files/saved-evidence/input.json | awk '{print $1}')
test "$expected" = "$actual"
class=com.yourapp.wine.textlab.SavedEvidenceReplayTest
flag=savedEvidenceReplay
seconds=$((168 * 60 + 180))
if [[ "${SEARCH_SWEEP:-false}" == true ]]; then
  class=com.yourapp.wine.textlab.SearchSweepDeviceTest
  flag=searchSweep
  seconds=$((6 * 168 * 60 + 180))
fi
if [[ "${RULE_ITERATION:-false}" == true ]]; then
  class=com.yourapp.wine.textlab.RuleIterationDeviceTest
  flag=ruleIteration
  seconds=$((7 * 168 * 60 + 180))
fi
if [[ "${APPELLATION_ITERATION:-false}" == true ]]; then
  class=com.yourapp.wine.textlab.AppellationFallbackDeviceTest
  flag=appellationFallback
  seconds=$((4 * 168 * 60 + 180))
fi
if [[ "${ANCHOR_ITERATION:-false}" == true ]]; then
  class=com.yourapp.wine.textlab.AnchorHybridDeviceTest
  flag=anchorHybrid
  seconds=$((4 * 168 * 60 + 180))
fi
if [[ "${PRODUCER_ITERATION:-false}" == true ]]; then
  class=com.yourapp.wine.textlab.ProducerRepairDeviceTest
  flag=producerRepair
  seconds=$((4 * 168 * 60 + 180))
fi
if [[ "${PRODUCER_REFINEMENT:-false}" == true ]]; then
  class=com.yourapp.wine.textlab.ProducerRefinementDeviceTest
  flag=producerRefinement
  seconds=$((4 * 168 * 60 + 180))
fi
if [[ "${PRODUCER_ROLES:-false}" == true ]]; then
  class=com.yourapp.wine.textlab.ProducerRoleDeviceTest
  flag=producerRole
  seconds=$((4 * 168 * 60 + 180))
fi
if [[ "${PRODUCER_LAB_VALIDATION:-false}" == true ]]; then
  class=com.yourapp.wine.textlab.ProducerLabValidationTest
  flag=producerLabValidation
  seconds=$((168 * 60 + 180))
fi
set +e
timeout --signal=TERM "$seconds" adb shell am instrument -w -r -e "$flag" true -e class "$class" "$pkg.test/androidx.test.runner.AndroidJUnitRunner" > "$root/instrumentation.txt" 2>&1
result=$?
set -e
adb exec-out run-as "$pkg" tar -cf - -C files/saved-evidence . > "$root/device-results.tar"
tar -xf "$root/device-results.tar" -C "$root"
cat "$root/instrumentation.txt"
test "$result" = 0
grep -q 'OK (1 test)' "$root/instrumentation.txt"
