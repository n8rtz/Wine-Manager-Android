#!/usr/bin/env bash
# Actual parser/policy/state code on JVM. Existing host geometry/log fixtures, NOT Android/Compose.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${JAVA_HOME:?Set JAVA_HOME}"
: "${KOTLIN_HOME:?Set KOTLIN_HOME to Kotlin 2.2.10}"
export PATH="$JAVA_HOME/bin:$PATH"
tmp=$(mktemp -d);trap 'rm -rf "$tmp"' EXIT
root=app/src/main/java/com/yourapp/wine
sources=("$root"/merge/*.kt "$root"/extract/*.kt "$root"/fill/*.kt)
for name in WineKnowledgeBase FixtureKnowledgeBase PlaceGazetteer PlaceHierarchy WineTerms BundledGrapes BundledWineStyles BundledProducerNames BundledOrigins ReferenceWineCatalog GeneratedWineKnowledge SupplementedWineKnowledgeBase; do
 sources+=("$root/kb/$name.kt")
done
bash "$KOTLIN_HOME/bin/kotlinc" "${sources[@]}" "$root/ocr/Tokens.kt" \
 "$root/ui/ScanStats.kt" "$root/ui/ScanFormState.kt" "$root/ui/ScanChoicePolicy.kt" \
 tools/logic-checks/AndroidGeometry.kt tools/logic-checks/AndroidLog.kt tools/logic-checks/ScanChoiceChecks.kt \
 -include-runtime -d "$tmp/choices.jar"
"$JAVA_HOME/bin/java" -jar "$tmp/choices.jar"
