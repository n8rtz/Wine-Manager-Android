#!/usr/bin/env bash
# Real Kotlin/JVM execution; no Android SDK, Actions, OCR, or network/downloads in this script.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${JAVA_HOME:?Set JAVA_HOME to a genuine JDK 17+}"
: "${KOTLIN_HOME:?Set KOTLIN_HOME to Kotlin compiler 2.2.10}"
export PATH="$JAVA_HOME/bin:$PATH"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
bash "$KOTLIN_HOME/bin/kotlinc" \
  app/src/debug/java/com/yourapp/wine/textlab/residual/ResidualTagger.kt \
  app/src/main/java/com/yourapp/wine/kb/WineTerms.kt \
  app/src/main/java/com/yourapp/wine/kb/BundledGrapes.kt \
  app/src/main/java/com/yourapp/wine/kb/BundledProducerNames.kt \
  app/src/main/java/com/yourapp/wine/kb/GeneratedWineKnowledge.kt \
  app/src/main/java/com/yourapp/wine/merge/TextNorm.kt \
  tools/logic-checks/ResidualTaggerCheck.kt -include-runtime -d "$tmp/residual-checks.jar"
"$JAVA_HOME/bin/java" -jar "$tmp/residual-checks.jar"
