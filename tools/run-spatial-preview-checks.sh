#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${JAVA_HOME:?Set JAVA_HOME}"
: "${KOTLIN_HOME:?Set KOTLIN_HOME to Kotlin 2.2.10}"
export PATH="$JAVA_HOME/bin:$PATH"
tmp=$(mktemp -d);trap 'rm -rf "$tmp"' EXIT
root=app/src/main/java/com/yourapp/wine
lab=app/src/debug/java/com/yourapp/wine/textlab
sources=("$root"/merge/*.kt "$root"/extract/*.kt "$root"/fill/*.kt "$lab"/evidence/*.kt)
for name in WineKnowledgeBase FixtureKnowledgeBase PlaceGazetteer PlaceHierarchy WineTerms BundledGrapes BundledWineStyles BundledProducerNames BundledOrigins ReferenceWineCatalog GeneratedWineKnowledge SupplementedWineKnowledgeBase; do
 sources+=("$root/kb/$name.kt")
done
bash "$KOTLIN_HOME/bin/kotlinc" "${sources[@]}" "$root/ocr/Tokens.kt" "$root/textlab/PaddleCandidates.kt" \
 "$lab"/{TextLabSpatialPhrases,TextLabSpatialPreview,TextLabEvidencePreview,TextLabFormPreview,TextLabJournal,TextLabWineTable,TextLabChoices}.kt \
 app/src/debug/java/com/yourapp/wine/gazetteer/ProducerResolver.kt \
 tools/logic-checks/{AndroidGeometry,AndroidLog,AndroidClock,SpatialPreviewChecks,ProducerResolverChecks}.kt \
 -cp "$KOTLIN_HOME/lib/kotlinx-coroutines-core-jvm.jar" -include-runtime -d "$tmp/checks.jar"
"$JAVA_HOME/bin/java" -cp "$tmp/checks.jar:$KOTLIN_HOME/lib/kotlinx-coroutines-core-jvm.jar" SpatialPreviewChecksKt

"$JAVA_HOME/bin/java" -cp "$tmp/checks.jar:$KOTLIN_HOME/lib/kotlinx-coroutines-core-jvm.jar" ProducerResolverChecksKt
