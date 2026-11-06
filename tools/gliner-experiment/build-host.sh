#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
: "${JAVA_HOME:?set JAVA_HOME to JDK 21}"
: "${KOTLIN_HOME:?set KOTLIN_HOME to Kotlin compiler 2.2.10}"
: "${JSON_JAR:?set JSON_JAR to an org.json implementation jar}"
export PATH="$JAVA_HOME/bin:$PATH"
out=${1:?output directory required}
mkdir -p "$out"
g=gliner-workflow/src/main/java/com/yourapp/wine/gliner
a=gliner-workflow/src/appIntegration/java/com/yourapp/wine/gliner
k=app/src/main/java/com/yourapp/wine/kb
merge=app/src/main/java/com/yourapp/wine/merge
"$KOTLIN_HOME/bin/kotlinc" tools/logic-checks/AndroidGeometry.kt "$g"/{GlinerDocument,GlinerLanes}.kt "$merge"/{TextNorm,BoxMath}.kt "$a/GlinerFusion.kt" tools/gliner-experiment/PrepareDocuments.kt -include-runtime -d "$out/documents.jar"
"$KOTLIN_HOME/bin/kotlinc" tools/logic-checks/AndroidGeometry.kt \
  "$g"/{GlinerDocument,GlinerLanes,GlinerEngines,GlinerGeography,GlinerNamedSites,GlinerFieldDecision}.kt \
  "$merge/TextNorm.kt" "$k"/{WineTerms,BundledGrapes,BundledWineStyles,BundledOrigins,ReferenceWineCatalog,GeneratedWineKnowledge,WineKnowledgeBase}.kt \
  "$a/GlinerCatalogIndexes.kt" tools/host-lab/GlinerDecisionReplay.kt -include-runtime -d "$out/decision.jar"

"$KOTLIN_HOME/bin/kotlinc" "$g/GlinerTokenizer.kt" tools/gliner-experiment/TokenizerParity.kt -cp "$JSON_JAR" -include-runtime -d "$out/tokenizer.jar"
cp "$JSON_JAR" "$out/json.jar"
