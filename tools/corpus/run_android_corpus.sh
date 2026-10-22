#!/usr/bin/env bash
# Called inside the CI emulator action, after host acquisition. Images never enter an APK/Git.
set -euo pipefail
: "${CORPUS_DIR:?}" "${CORPUS_RESULTS:?}" "${CORPUS_LIMIT:=3}"
pkg=com.n8rtz.winetracker
mkdir -p "$CORPUS_RESULTS"
cp "$CORPUS_DIR/manifest.json" "$CORPUS_RESULTS/acquisition-manifest.json"
adb shell getprop > "$CORPUS_RESULTS/emulator-properties.txt"
# Keep the shipped arm64 native libraries. The Google APIs image must expose an ARM64 native bridge.
adb shell getprop ro.product.cpu.abilist | grep -q arm64-v8a || {
  echo 'Emulator cannot run shipped arm64 libraries; refusing to substitute an OCR stack.' >&2; exit 1;
}
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell run-as "$pkg" mkdir -p files/private-corpus/images
python3 - "$CORPUS_DIR" "$CORPUS_LIMIT" > "$CORPUS_RESULTS/selected-images.txt" <<'PY'
import json, sys
from pathlib import Path
root=Path(sys.argv[1]); limit=int(sys.argv[2]); assert 1<=limit<=200
rows=json.loads((root/'manifest.json').read_text())['records']
# Source identity remains recoverable from the manifest; exact duplicate files run only once.
for name in sorted({r['file'] for r in rows})[:limit]: print(root/name)
PY
while IFS= read -r file; do
  adb exec-in run-as "$pkg" tee "files/private-corpus/images/$(basename "$file")" < "$file" > /dev/null
done < "$CORPUS_RESULTS/selected-images.txt"
set +e
timeout --signal=TERM "$((CORPUS_LIMIT * 150 + 180))" adb shell am instrument -w -r -e class com.yourapp.wine.textlab.PublicCorpusDeviceTest \
  -e privateCorpus true "$pkg.test/androidx.test.runner.AndroidJUnitRunner" > "$CORPUS_RESULTS/instrumentation.txt" 2>&1
result=$?
set -e
adb exec-out run-as "$pkg" tar -cf - -C files/private-corpus results > "$CORPUS_RESULTS/results.tar"
tar -xf "$CORPUS_RESULTS/results.tar" -C "$CORPUS_RESULTS"
adb logcat -d > "$CORPUS_RESULTS/logcat.txt"
cat "$CORPUS_RESULTS/instrumentation.txt"
# am instrument can exit zero even for a failed test.
test "$result" = 0
grep -q 'OK (1 test)' "$CORPUS_RESULTS/instrumentation.txt"
