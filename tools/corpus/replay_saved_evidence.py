#!/usr/bin/env python3
"""Prepare saved OCR only; score Android replay separately against frozen references."""
import argparse
import csv
import gzip
import json
from pathlib import Path
from score_public_corpus import load, score, digest, FIELDS
ROOT=Path(__file__).resolve().parents[2]
RUN=ROOT/'tools/evaluation/public-corpus-run-39716483937'
REF=ROOT/'tools/evaluation/public-corpus-reference-v1'

def prepare(path):
    ref,pred,prod=load(REF,RUN)
    groups={i['image']:[] for i in ref['images']}
    with (RUN/'ocr-comparison.csv').open() as f:
        for row in csv.DictReader(f):
            if row['stage'] in ('recognition','summary','candidate_summary'):
                groups[row['image_name']].append(row)
    items=[]
    for image,rows in sorted(groups.items()):
        items.append(dict(image=image,rows=rows))
    path.write_text(json.dumps(items,ensure_ascii=False))

def collect(root,out,sha):
    validation=json.loads((root/'validation.json').read_text())
    assert validation.get('images')==168 and validation.get('execution_policy')=='revised_experimental_only',validation
    assert validation.get('baseline_executions')==0 and validation.get('old_experimental_executions')==0,validation
    assert validation.get('completed')==168 and validation.get('failures')==[],validation
    assert validation.get('corpus_budget_ms')==60000,validation
    records=[json.loads(line) for line in (root/'results.jsonl').read_text().splitlines()]
    ref,pred,prod=load(REF,RUN)
    assert len(records)==168 and {r['image'] for r in records}==set(prod)
    old,_=score(ref,pred,prod)
    for r in records:
        assert r['build']==sha[:12]
        assert set(r['fields'])==set(FIELDS)
        pred['experimental'][r['image']]={f:(r['fields'][f]['value'],r['fields'][f]['state']) for f in FIELDS}
    new,rows=score(ref,pred,prod)
    out.mkdir(parents=True,exist_ok=True)
    data=dict(source_commit=sha,reference_sha256=digest(REF/'reference.json'),
              input_sha256=digest(root/'input.json'),execution_validation=validation,
              old_experimental=old['pipelines']['experimental'],new_experimental=new['pipelines']['experimental'],
              unchanged_lab_baseline=old['pipelines']['lab_baseline'])
    (out/'scores.json').write_text(json.dumps(data,indent=2)+'\n')
    with gzip.open(out/'android-traces.jsonl.gz','wb') as f:f.write((root/'results.jsonl').read_bytes())
    with (out/'field-results.csv').open('w',newline='') as f:
        writer=csv.DictWriter(f,fieldnames=list(rows[0]));writer.writeheader();writer.writerows(r for r in rows if r['pipeline']=='experimental')
    lines=['# Saved-OCR identity-role iteration','',
           'Real Android/Kotlin replay of all 168 frozen lab OCR inputs. Production untouched. Only the revised experimental extractor executes. Baseline and v2 outputs are reused from the frozen run, not re-executed. The revised extractor has a 60-second maximum per image (historical v2 used two seconds), so budget changes are disclosed alongside assignment changes. This is development-set feedback, not held-out accuracy.','',
           '| Extractor | Correct | Wrong | Abstained | Six fields correct / eligible |','|---|---:|---:|---:|---:|']
    for label,key in [('Lab baseline','unchanged_lab_baseline'),('Experimental v2','old_experimental'),('Experimental v3','new_experimental')]:
        p=data[key];s=p['all_fields'];i=p['image_results']
        lines.append(f"| {label} | {s['correct']} | {s['wrong']} | {s['abstained']} | {i['six_field_fully_correct']} / {i['six_field_eligible']} |")
    lines+=['','894 known targets; 114 unknown targets excluded. Single-agent visual reference and aliases remain unchanged. See scores.json for per-field and duplicate-sensitivity results. Traces distinguish filtering, ownership conflicts, bounded search and competing finalists.','']
    (out/'report.md').write_text('\n'.join(lines))

if __name__=='__main__':
    p=argparse.ArgumentParser();sub=p.add_subparsers(dest='cmd',required=True)
    a=sub.add_parser('prepare');a.add_argument('path',type=Path)
    a=sub.add_parser('collect');a.add_argument('root',type=Path);a.add_argument('output',type=Path);a.add_argument('--sha',required=True)
    a=p.parse_args()
    if a.cmd=='prepare':prepare(a.path)
    else:collect(a.root,a.output,a.sha)
