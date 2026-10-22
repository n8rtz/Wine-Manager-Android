#!/usr/bin/env python3
"""Generate host replay from observed OCR, not ground truth. No model inference or downloads."""
import json
from pathlib import Path
root=Path(__file__).resolve().parents[2]
data=json.loads((root/'app/src/testDebug/resources/textlab/production-review.json').read_text())
def literal(s): return json.dumps(s,ensure_ascii=False).replace('$',r'\$')
body=[]
for case in data['cases']:
    records=[]
    for r in case['readings']:
        b=','.join(v+'f' for v in r['base_box_normalized'].split())
        orig=','.join(v+'f' for v in r['original_box_normalized'].split())
        text=literal(r['raw_text'])
        engine='mlkit' if case['engine']=='mlkit_reference' else 'paddle-v6-medium'
        confidence=float(r['ctc_posterior'] or r['mlkit_confidence'])
        estimated=r['confidence_estimated'].lower()=='true'
        records.append(f'TokenRecord({text},TextNorm.normalize({text}),{confidence}f,RectF({orig}),RectF({b}),emptyList(),"{engine}","V0",{str(estimated).lower()})')
    old=[case['observed_fields'][k] for k in ['producer','wine_name','country','region','subregion','vintage']]
    body.append('Case('+literal(case['image_index'])+','+literal(case['engine'])+',listOf('+','.join(map(literal,old))+'),listOf('+','.join(records)+'))')
source='''package com.yourapp.wine.textlab
import android.graphics.RectF
import com.yourapp.wine.fill.*
import com.yourapp.wine.kb.FixtureKnowledgeBase
import com.yourapp.wine.ocr.TokenRecord
import com.yourapp.wine.merge.TextNorm
import java.io.File
object PostProcessingReplay {
 data class Case(val image:String,val engine:String,val before:List<String>,val records:List<TokenRecord>)
 fun run() {
  val cases=listOf(RECORDS)
  val kb=FixtureKnowledgeBase()
  val lab=TextLabFieldExtractor(kb)
  val production=FillEngine(kb)
  val rows=mutableListOf(listOf("image","path","engine","producer","wine_name","country","region","subregion","vintage","role_lines","elapsed_ms","wine_reason","wine_alternatives","roles"))
  fun row(image:String,path:String,engine:String,result:FillResult,ms:Double) {
   check(result.fields.values.none { it.state==UiState.RED && it.value.isNotEmpty() })
   rows+=listOf(image,path,engine)+FieldId.entries.map { result.fields.getValue(it).value }+listOf(result.postProcessing.size.toString(),ms.toString(),result.fields.getValue(FieldId.WINE_NAME).reason,result.alternatives[FieldId.WINE_NAME].orEmpty().joinToString(" | ") { it.value },result.postProcessing.joinToString(" | "))
  }
  for(case in cases) {
   rows+=listOf(case.image,"old phone lab (different KB possible)",case.engine)+case.before+listOf("","","","","")
   val records=case.records.map { it.copy(score=.5f,scoreIsEstimated=true) }
   val start=System.nanoTime()
   val result=lab.extract(records,2026)
   check(result.fields.values.none { it.state==UiState.GREEN })
   row(case.image,"new host lab",case.engine,result,(System.nanoTime()-start)/1e6)
  }
  // Same saved crop coordinates; replay only, not a phone shutter/deadline benchmark.
  for((image,group) in cases.groupBy { it.image }) {
   val start=System.nanoTime()
   val result=CellarFieldPresentation.apply(production.fill(group.flatMap { it.records },2026))
   row(image,"combined production-rule replay","mlkit+paddle-v6-medium",result,(System.nanoTime()-start)/1e6)
  }
  fun cell(value:String)="\\\""+value.replace("\\\"","\\\"\\\"")+"\\\""
  File(".ocr-harness/checks/postprocessing-replay.csv").writeText(rows.joinToString("\\n") { it.joinToString(",",transform=::cell) })
  println("PASS: 12 lab cases and 6 combined production-rule replays; observed outputs, not accuracy scores")
 }
}
'''.replace('RECORDS',',\n'.join(body))
out=root/'.ocr-harness/checks/PostProcessingReplay.kt'
out.parent.mkdir(parents=True,exist_ok=True)
out.write_text(source)
