#!/usr/bin/env bash
# Limited host logic check, NOT a Gradle/Android build. Uses the real Kotlin compiler.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${KOTLIN_HOME:=$PWD/.ocr-harness/tools/kotlin/package}"
: "${JAVA_HOME:=$PWD/.ocr-harness/tools/jdk4py/java-runtime}"
if [[ ! -x "$KOTLIN_HOME/bin/kotlinc" || ! -x "$JAVA_HOME/bin/java" ]]; then
    echo "Set JAVA_HOME and KOTLIN_HOME to real JDK/Kotlin installations; see docs/offline-scanner.md." >&2
    exit 1
fi
export JAVA_HOME PATH="$JAVA_HOME/bin:$PATH"
if [[ -z "${ANDROID_JAR:-}" ]]; then
    sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
    if [[ -n "$sdk" && -f "$sdk/platforms/android-36/android.jar" ]]; then
        ANDROID_JAR="$sdk/platforms/android-36/android.jar"
    else
        ANDROID_JAR="$PWD/.ocr-harness/android/android.jar"
    fi
fi
if [[ ! -f "$ANDROID_JAR" ]]; then
    echo "Set ANDROID_JAR to a real SDK android.jar for the streaming adapter API types (JSON needs Robolectric)." >&2
    exit 1
fi
mkdir -p .ocr-harness/checks
python3 tools/logic-checks/generate-fixture-checks.py
root=app/src/main/java/com/yourapp/wine
sources=()
while IFS= read -r source; do sources+=("$source"); done < <(find "$root/merge" "$root/extract" "$root/fill" -name '*.kt' -print)
sources+=("$root/ocr/Tokens.kt" "$root/geom/Matrix3.kt" "$root/geom/CoordMapper.kt" "$root/geom/QuadOrder.kt" "$root/geom/LabelGeometryPolicy.kt" "$root/geom/TextHullClusterer.kt" "$root/ui/ScanFormState.kt" "$root/kb/WineKnowledgeBase.kt" "$root/kb/FixtureKnowledgeBase.kt" "$root/kb/PlaceGazetteer.kt" "$root/kb/PlaceHierarchy.kt"
    "$root/ocr/OcrImageTreatments.kt" "$root/ocr/OcrVariantGeometry.kt" "$root/ocr/OcrRescuePolicy.kt" "$root/ocr/OcrEngine.kt" "$root/ocr/OcrEnsemble.kt" "$root/ocr/ScanDeadline.kt" "$root/ocr/NativeEngineGate.kt"
    "$root/kb/WineTerms.kt" "$root/kb/BundledGrapes.kt" "$root/kb/BundledWineStyles.kt" "$root/kb/BundledProducerNames.kt" "$root/kb/BundledOrigins.kt" "$root/kb/ReferenceWineCatalog.kt" "$root/kb/GeneratedWineKnowledge.kt" "$root/kb/SupplementedWineKnowledgeBase.kt"
    "$root/capture/ScanReport.kt" "$root/capture/SelectedLabelPhotoImporter.kt"
    "$root/diagnostics/ScanDiagnostics.kt" "$root/diagnostics/ScanTraceRecorder.kt" "$root/diagnostics/ScanDiagnosticFormatter.kt"
    app/src/main/java/com/n8rtz/winetracker/scanner/LabelScanState.kt
    "$root/capture/BitmapWorkspace.kt" "$root/kb/OpenWinesAdapter.kt" "$root/kb/KnowledgeRecord.kt"
    "$root/kb/CsvKnowledgeRecords.kt" "$root/kb/JsonKnowledgeRecords.kt" "$root/kb/ImportReaders.kt"
    app/src/test/java/com/yourapp/wine/LogicChecks.kt app/src/test/java/com/yourapp/wine/DiagnosticChecks.kt app/src/test/java/com/yourapp/wine/LocalizationChecks.kt app/src/test/java/com/yourapp/wine/AppellationContextChecks.kt app/src/test/java/com/yourapp/wine/ReferenceCatalogChecks.kt app/src/test/java/com/yourapp/wine/AdministrativeLabelChecks.kt app/src/test/java/com/yourapp/wine/LabelNameChecks.kt app/src/test/java/com/yourapp/wine/ReportedWineChecks.kt app/src/test/java/com/yourapp/wine/EnsemblePolicyChecks.kt
    tools/logic-checks/AndroidGeometry.kt tools/logic-checks/AndroidClock.kt tools/logic-checks/AndroidLog.kt
    tools/logic-checks/HostBitmap.kt tools/logic-checks/DeadlineChecks.kt tools/logic-checks/ImportChecks.kt tools/logic-checks/Main.kt
    app/src/debug/java/com/yourapp/wine/textlab/PaddleDetectionGeometry.kt
    app/src/testDebug/java/com/yourapp/wine/textlab/PaddleDetectionChecks.kt
    app/src/debug/java/com/yourapp/wine/textlab/PaddleCandidates.kt
    app/src/debug/java/com/yourapp/wine/textlab/TextLabJournal.kt
    app/src/testDebug/java/com/yourapp/wine/textlab/TextLabJournalChecks.kt
    app/src/debug/java/com/yourapp/wine/textlab/PaddleProbabilityMap.kt
    app/src/testDebug/java/com/yourapp/wine/textlab/PaddleProbabilityChecks.kt
    app/src/debug/java/com/yourapp/wine/textlab/PaddleCtc.kt
    app/src/testDebug/java/com/yourapp/wine/textlab/PaddleCtcChecks.kt
    app/src/debug/java/com/yourapp/wine/textlab/TextCropGeometry.kt
    app/src/testDebug/java/com/yourapp/wine/textlab/TextCropChecks.kt
    app/src/debug/java/com/yourapp/wine/benchmark/CellarWineChoice.kt
    app/src/testDebug/java/com/yourapp/wine/benchmark/CellarWineChoiceChecks.kt
    app/src/debug/java/com/yourapp/wine/benchmark/BenchmarkCsv.kt
    app/src/debug/java/com/yourapp/wine/benchmark/BenchmarkHistory.kt
    app/src/testDebug/java/com/yourapp/wine/benchmark/BenchmarkCsvChecks.kt
    app/src/debug/java/com/yourapp/wine/benchmark/BenchmarkPlan.kt
    app/src/debug/java/com/yourapp/wine/benchmark/BenchmarkScoring.kt
    app/src/debug/java/com/yourapp/wine/benchmark/BenchmarkGeometry.kt
    app/src/testDebug/java/com/yourapp/wine/benchmark/BenchmarkChecks.kt
    .ocr-harness/checks/GeneratedFixtureChecks.kt)
coroutines="$KOTLIN_HOME/lib/kotlinx-coroutines-core-jvm.jar"
"$KOTLIN_HOME/bin/kotlinc" -classpath "$coroutines:$ANDROID_JAR" "${sources[@]}" -d .ocr-harness/checks/logic.jar
"$KOTLIN_HOME/bin/kotlin" -classpath ".ocr-harness/checks/logic.jar:$coroutines:$ANDROID_JAR" MainKt
