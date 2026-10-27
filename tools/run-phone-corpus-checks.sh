#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${JAVA_HOME:?Set JAVA_HOME to Java 21}"
: "${KOTLIN_HOME:?Set KOTLIN_HOME to Kotlin 2.2.10}"
export PATH="$JAVA_HOME/bin:$PATH"
tmp=$(mktemp -d);trap 'rm -rf "$tmp"' EXIT
python3 tools/corpus/phone_score_fixture.py > "$tmp/fixture.tsv"
bash "$KOTLIN_HOME/bin/kotlinc" \
 app/src/main/java/com/yourapp/wine/merge/TextNorm.kt \
 app/src/main/java/com/yourapp/wine/phonecorpus/PhoneCorpusMath.kt \
 app/src/main/java/com/yourapp/wine/textlab/{TextLabStats,TextLabJournal,TextLabChoices}.kt \
 app/src/main/java/com/yourapp/wine/textlab/PaddleCandidates.kt \
 tools/logic-checks/PhoneCorpusChecks.kt -include-runtime -d "$tmp/checks.jar"
"$JAVA_HOME/bin/java" -jar "$tmp/checks.jar" "$tmp/fixture.tsv"
