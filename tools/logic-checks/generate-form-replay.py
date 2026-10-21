#!/usr/bin/env python3
"""Replay observed batch text through the actual combined-preview adapter; no OCR inference."""
import json
import sys
from pathlib import Path
root=Path(__file__).resolve().parents[2]
evidence='--evidence' in sys.argv
layout='--layout' in sys.argv
data=json.loads((root/('app/src/testDebug/resources/textlab/evidence-review.json' if evidence else 'app/src/testDebug/resources/textlab/layout-review.json' if layout else 'app/src/testDebug/resources/textlab/form-review.json')).read_text())
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
if layout:
    start=source.index('  check(summaries[1]')
    end=source.index('  java.io.File',start)
    source=source[:start]+'''  val expected=listOf(
   listOf("Kirkland Signature","Brunello di Montalcino","Italy","Tuscany","Montalcino","2021"),
   listOf("Marqués de Riscal","Verdejo","Spain","Castilla y Leon","Rueda","2023"),
   listOf("Kirkland Signature","Cabernet Sauvignon","California","Napa Valley","","2024"),
   listOf("Kirkland Signature","Cabernet Sauvignon","California","Alexander Valley","","2022"),
   listOf("Blackstone","Merlot","California","","",""),
   listOf("Vignerons De L'ENclave","LA Reserve de L'ENclave","France","Rhône","Côtes du Rhône Villages","2022"))
  val keys=listOf("producer","wine_name","country","region","subregion","vintage")
  summaries.zip(expected).forEach { (row,want) -> check(keys.map { row[it].orEmpty() }==want) { row } }
  for(key in listOf("producer","wine_name","subregion")) check(summaries.last()[key+"_state"]=="YELLOW")
'''+source[end:]
    source=source.replace('FormBatchReplay','LayoutBatchReplay').replace('form-preview-replay.csv','layout-preview-replay.csv')
if evidence:
    start=source.index('  check(summaries[1]')
    end=source.index('  java.io.File',start)
    source=source[:start]+'''  val expected=listOf(
   listOf("Kirkland Signature","Cabernet Sauvignon","California","Napa Valley","","2024"),
   listOf("Kirkland Signature","Brunello di Montalcino","Italy","Tuscany","Montalcino","2021"),
   listOf("Marqués de Riscal","Verdejo","Spain","Castilla y Leon","Rueda","2023"),
   listOf("Kirkland Signature","Cabernet Sauvignon","California","Alexander Valley","","2022"),
   listOf("Blackstone","Merlot","California","","",""),
   listOf("Vignerons De L'ÊNCLaVE","LA Reserve de L'ÊNCLaVE","","","","2022"))
  val keys=listOf("producer","wine_name","country","region","subregion","vintage")
  summaries.zip(expected).forEach { (row,want) -> check(keys.map { row[it].orEmpty() }==want) { row } }
  for(key in listOf("producer","wine_name")) check(summaries.last()[key+"_state"]=="YELLOW")
'''+source[end:]
    source=source.replace('FormBatchReplay','EvidenceBatchReplay').replace('form-preview-replay.csv','evidence-preview-replay.csv')
p=root/('.ocr-harness/checks/EvidenceBatchReplay.kt' if evidence else '.ocr-harness/checks/LayoutBatchReplay.kt'  if layout else '.ocr-harness/checks/FormBatchReplay.kt');p.parent.mkdir(parents=True,exist_ok=True);p.write_text(source)
