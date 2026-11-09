#!/usr/bin/env bash
# Host GLiNER-lane replay: stages 1b/3 of the new architecture over the packaged cached OCR.
#
# What runs for real: GlinerFusion (both engines -> one word sequence), GlinerDocument (banding,
# word flattening), GlinerLaneRecords (lane/residual -> per-engine TokenRecords) and the unchanged
# Lab v7 post-processing (TextLabFormPreview + TextLabSpatialPreview).
# What is a stand-in: stage 2, the GLiNER-Small INT4 graph, whose weights are not in this
# repository. `none` sends no lane at all; `oracle` matches the reference answers to word spans,
# i.e. a perfect labeller, which makes the run a ceiling rather than a prediction.
#
# Usage: bash tools/run-host-gliner-lane.sh [none|oracle] [tag]
# Compare with the Lab v7 baseline by running `bash tools/run-host-lab.sh` first.
set -euo pipefail
cd "$(dirname "$0")/.."
mode=${1:-oracle}
tag=${2:-"$mode"}
: "${JAVA_HOME:=$PWD/.ocr-harness/tools/jdk4py/java-runtime}"
: "${KOTLIN_HOME:=$PWD/.ocr-harness/tools/kotlin/package}"
if [[ ! -x "$KOTLIN_HOME/bin/kotlinc" || ! -x "$JAVA_HOME/bin/java" ]]; then
    echo "Set JAVA_HOME (JDK 21) and KOTLIN_HOME (Kotlin 2.2.10); see tools/host-lab/README.md." >&2
    exit 1
fi
export PATH="$JAVA_HOME/bin:$PATH"
out=tools/host-lab/out/gliner-$tag
baseline=tools/host-lab/out/baseline/output.tsv
mkdir -p "$out"
python3 tools/host-lab/host_lab.py prepare --output "$out/input.tsv"
python3 tools/host-lab/host_lab.py refs --output "$out/refs.tsv"

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
gliner=gliner-workflow/src/main/java/com/yourapp/wine/gliner
glinerAdapter=gliner-workflow/src/appIntegration/java/com/yourapp/wine/gliner
sources=("$root"/merge/*.kt "$root"/extract/*.kt "$root"/fill/*.kt "$lab"/evidence/*.kt)
for name in WineKnowledgeBase FixtureKnowledgeBase PlaceGazetteer PlaceHierarchy WineTerms BundledGrapes BundledWineStyles BundledProducerNames BundledOrigins ReferenceWineCatalog GeneratedWineKnowledge SupplementedWineKnowledgeBase; do
    sources+=("$root/kb/$name.kt")
done
bash "$KOTLIN_HOME/bin/kotlinc" "${sources[@]}" \
    "$root/ocr/Tokens.kt" "$root/geom/Matrix3.kt" "$root/textlab/PaddleCandidates.kt" \
    "$lab"/{TextLabSpatialPhrases,TextLabSpatialPreview,TextLabEvidencePreview,TextLabFormPreview,TextLabJournal,TextLabWineTable,TextLabChoices,TextCropGeometry,TextLabBottleFilter}.kt \
    app/src/main/java/com/yourapp/wine/gazetteer/{ProducerResolver,LinkedWineResolver}.kt \
    app/src/main/java/com/yourapp/wine/diagnostics/PipelineFunnel.kt \
    "$gliner"/{GlinerEngines,GlinerLanes,GlinerDocument}.kt \
    "$glinerAdapter"/{GlinerFusion,GlinerLaneRecords}.kt \
    tools/logic-checks/{AndroidGeometry,AndroidLog,AndroidClock}.kt \
    tools/host-lab/HostGlinerLaneReplay.kt \
    -cp "$KOTLIN_HOME/lib/kotlinx-coroutines-core-jvm.jar" -include-runtime -d "$tmp/lane.jar"
"$JAVA_HOME/bin/java" -cp "$tmp/lane.jar:$KOTLIN_HOME/lib/kotlinx-coroutines-core-jvm.jar" \
    HostGlinerLaneReplayKt "$out/input.tsv" "$out/output.tsv" "$PWD" \
    "blank_subregion_fuzzy+vintage_recovery+final_producer_near_match+producer_role_particle+spatial_semantic_guards" \
    2026 "$mode" "$out/refs.tsv" "$out/stats.tsv"

if [[ -f "$baseline" ]]; then
    python3 tools/host-lab/host_lab.py score "$out/output.tsv" --report "$out/report.md" --baseline-output "$baseline"
else
    python3 tools/host-lab/host_lab.py score "$out/output.tsv" --report "$out/report.md"
fi
