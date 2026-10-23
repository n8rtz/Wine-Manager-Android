#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${JAVA_HOME:?Set JAVA_HOME to Java 21}"
: "${KOTLIN_HOME:?Set KOTLIN_HOME to Kotlin 2.2.10}"
export PATH="$JAVA_HOME/bin:$PATH"
tmp=$(mktemp -d);trap 'rm -rf "$tmp"' EXIT
bash "$KOTLIN_HOME/bin/kotlinc" app/src/debug/java/com/yourapp/wine/phonecorpus/CsvUploadParts.kt tools/logic-checks/CsvUploadPartsChecks.kt -include-runtime -d "$tmp/checks.jar"
"$JAVA_HOME/bin/java" -jar "$tmp/checks.jar"
