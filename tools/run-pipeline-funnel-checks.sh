#!/usr/bin/env bash
# Actual Kotlin aggregation/checkpoint/scorer/CSV logic; host asset/mode stand-ins, no Android/OCR.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${JAVA_HOME:?Set JAVA_HOME to Java 21}"
: "${KOTLIN_HOME:?Set KOTLIN_HOME to Kotlin 2.2.10}"
: "${JSON_JAVA_CLASSPATH:?Set JSON_JAVA_CLASSPATH to a JSON-java jar or compiled classes directory}"
export PATH="$JAVA_HOME/bin:$PATH"
tmp=$(mktemp -d);trap 'rm -rf "$tmp"' EXIT
output="$tmp/funnel.csv"
fixture_args=()
while (($#)); do
    case "$1" in
        --output) output="$2";shift 2 ;;
        --scored|--raw) fixture_args+=("$1" "$2");shift 2 ;;
        *) echo "Unknown argument: $1" >&2;exit 2 ;;
    esac
done
python3 tools/logic-checks/generate-funnel-fixture.py "$tmp/fixture.json" "${fixture_args[@]}"
bash "$KOTLIN_HOME/bin/kotlinc" \
 app/src/debug/java/com/yourapp/wine/diagnostics/PipelineFunnel.kt \
 app/src/debug/java/com/yourapp/wine/phonecorpus/{PhoneCorpusMath,PhoneCorpusPipelineDiagnostics,PhoneCorpusFunnelJson,PhoneCorpusScoreboard}.kt \
 app/src/debug/java/com/yourapp/wine/gazetteer/{ProducerResolver,LinkedWineResolver}.kt \
 app/src/debug/java/com/yourapp/wine/textlab/{TextLabJournal,TextLabChoices}.kt \
 app/src/main/java/com/yourapp/wine/textlab/PaddleCandidates.kt \
 tools/logic-checks/{PipelineFunnelChecks,PipelineFunnelHostContext,PipelineFunnelHostRunner}.kt \
 -cp "$JSON_JAVA_CLASSPATH" -include-runtime -d "$tmp/checks.jar"
"$JAVA_HOME/bin/java" -cp "$tmp/checks.jar:$JSON_JAVA_CLASSPATH" PipelineFunnelChecksKt "$PWD" "$tmp/fixture.json" "$output"
if [[ "$output" != "$tmp/funnel.csv" ]]; then printf 'Saved scored results + pipeline table: %s\n' "$output";fi
