#!/usr/bin/env bash
# Host Lab v5 replay: compiles the real Lab post-OCR Kotlin sources (same set
# as run-spatial-preview-checks.sh) plus HostLabReplay.kt and runs all cached-
# OCR journals on the JVM. NOT an Android build; no OCR, timing or budget
# behaviour is validated. See docs/lab-v5-124-diagnostic-a709ad6.md.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${JAVA_HOME:=$PWD/.ocr-harness/tools/jdk4py/java-runtime}"
: "${KOTLIN_HOME:=$PWD/.ocr-harness/tools/kotlin/package}"
if [[ ! -x "$KOTLIN_HOME/bin/kotlinc" || ! -x "$JAVA_HOME/bin/java" ]]; then
    echo "Set JAVA_HOME (JDK 21) and KOTLIN_HOME (Kotlin 2.2.10); see tools/host-lab/README.md." >&2
    exit 1
fi
export PATH="$JAVA_HOME/bin:$PATH"
input=${1:?input.tsv}; output=${2:?output.tsv}; modules=${3:?modules signature key}
tmp=$(mktemp -d)
HOST_PYTHON=${HOST_PYTHON:-/usr/bin/python3}
"$HOST_PYTHON" tools/host-lab/sqlite_bridge.py \
    app/src/main/assets/producer-gazetteer-expanded-v1/producers-expanded.sqlite \
    app/src/main/assets/producer-gazetteer-v1/wines.sqlite \
    --serve "$tmp/bridge.port" &
bridge_pid=$!
trap 'kill "$bridge_pid" 2>/dev/null || true; rm -rf "$tmp"' EXIT
for _ in $(seq 1 100); do [[ -s "$tmp/bridge.port" ]] && break; sleep 0.1; done
export HOST_BRIDGE_PORT=$(cat "$tmp/bridge.port")
root=app/src/main/java/com/yourapp/wine
lab=app/src/main/java/com/yourapp/wine/textlab
sources=("$root"/merge/*.kt "$root"/extract/*.kt "$root"/fill/*.kt "$lab"/evidence/*.kt)
for name in WineKnowledgeBase FixtureKnowledgeBase PlaceGazetteer PlaceHierarchy WineTerms BundledGrapes BundledWineStyles BundledProducerNames BundledOrigins ReferenceWineCatalog GeneratedWineKnowledge SupplementedWineKnowledgeBase; do
 sources+=("$root/kb/$name.kt")
done
bash "$KOTLIN_HOME/bin/kotlinc" "${sources[@]}" "$root/ocr/Tokens.kt" "$root/geom/Matrix3.kt" "$root/textlab/PaddleCandidates.kt" \
 "$lab"/{TextLabSpatialPhrases,TextLabSpatialPreview,TextLabEvidencePreview,TextLabFormPreview,TextLabJournal,TextLabWineTable,TextLabChoices,TextCropGeometry,TextLabBottleFilter}.kt \
 app/src/main/java/com/yourapp/wine/gazetteer/{ProducerResolver,LinkedWineResolver}.kt \
 app/src/main/java/com/yourapp/wine/diagnostics/PipelineFunnel.kt \
 tools/logic-checks/{AndroidGeometry,AndroidLog,AndroidClock,HostLabReplay}.kt \
 -cp "$KOTLIN_HOME/lib/kotlinx-coroutines-core-jvm.jar" -include-runtime -d "$tmp/replay.jar"
"$JAVA_HOME/bin/java" -cp "$tmp/replay.jar:$KOTLIN_HOME/lib/kotlinx-coroutines-core-jvm.jar" HostLabReplayKt \
 "$input" "$output" "$PWD" "$modules" 2026 "${DEBUG_ROWS:-}"
