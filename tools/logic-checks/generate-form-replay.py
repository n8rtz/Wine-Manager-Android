#!/usr/bin/env python3
"""Replay observed batch text through the actual combined-preview adapter; no OCR inference."""
import json
from pathlib import Path
root=Path(__file__).resolve().parents[2]
data=json.loads((root/'app/src/testDebug/resources/textlab/form-review.json').read_text())
def q(s): return json.dumps(s,ensure_ascii=False).replace('$',r'\$')
images=','.join('TextLabImageKey('+q(c['image_index'])+','+q(c['image_name'])+')' for c in data['cases'])
lines=[]
for i,c in enumerate(data['cases']):
    for r in c['readings']:
        fields=','.join(q(k)+' to '+q(v) for k,v in r.items() if k!='candidate')
        lines.append(f'batch.journals[{i}].add({q(r["candidate"])},"recognition",values=mapOf({fields}))')
    for candidate,stage in [('mlkit_reference','summary'),('candidate_3','candidate_summary')]:
        lines.append(f'batch.journals[{i}].add("{candidate}","{stage}","complete")')
source='''package com.yourapp.wine.textlab
object FormBatchReplay {
 fun run() {
  val batch=TextLabBatch(listOf(IMAGES),setOf("candidate_3"),"host-replay",true)
  LINES
  val engine=com.yourapp.wine.fill.FillEngine(com.yourapp.wine.kb.FixtureKnowledgeBase())
  batch.journals.forEach { TextLabFormPreview.append(it,engine,2026) }
  check(batch.journals.all { j -> j.rows.single { it["stage"]=="form_summary" }["status"]!="failed" })
  val summaries=batch.journals.map { j -> j.rows.single { it["stage"]=="form_summary" } }
  check(summaries[1]["wine_name"]=="Brunello di Montalcino" && summaries[1]["subregion"]=="Montalcino" && summaries[1]["region"]=="Tuscany")
  check(summaries[3]["region"]=="Alexander Valley" && summaries[3]["subregion"].orEmpty().isBlank())
  check(summaries.all { it["subregion"].orEmpty().isBlank() || it["region"].orEmpty().isNotBlank() })
  check(summaries[0]["wine_name"].orEmpty().isBlank())
  check(summaries[2]["wine_name"]=="Verdejo")
  check(com.yourapp.wine.extract.MetadataRules.isPackaging("M.Bby Vol."))
  check(!com.yourapp.wine.extract.MetadataRules.isPackaging("Volnay"))
  java.io.File(".ocr-harness/checks/form-preview-replay.csv").bufferedWriter().use(batch::writeCsv)
  println("PASS: latest six-image batch replayed through combined form adapter (not new OCR/device timing)")
 }
}
'''.replace('IMAGES',images).replace('LINES','\n'.join(lines))
p=root/'.ocr-harness/checks/FormBatchReplay.kt';p.parent.mkdir(parents=True,exist_ok=True);p.write_text(source)
