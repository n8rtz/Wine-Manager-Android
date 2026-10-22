#!/usr/bin/env python3
"""Validate cached producer implementation against frozen preceding outputs, not targets."""
import argparse,gzip,json,tempfile
from pathlib import Path
from producer_iteration import prepare as prepare_original,ROOT
from score_public_corpus import digest
SOURCE=ROOT/'tools/evaluation/producer-refinement-39857230621/android-results.jsonl.gz'
def prepare(path):
    prepare_original(path)
    prior={r['image']:r['fields']['producer']['value'] for r in map(json.loads,gzip.open(SOURCE,'rt')) if r['variant']=='producer-refined-2'}
    items=json.loads(path.read_text());assert len(prior)==168
    for i in items:
        i['preceding_producer_output']=prior[i['image']]
        del i['producer_alternatives']
    path.write_text(json.dumps(items,ensure_ascii=False))
def collect(root,out,sha):
    v=json.loads((root/'validation.json').read_text());assert v['images']==168 and v['mismatches']==[] and v['baseline_executions']==v['old_experimental_executions']==0
    assert len(v['elapsed_ms'])==168 and all(isinstance(t,(int,float)) and 0<=t<=60000 for t in v['elapsed_ms'])
    assert v['lab_synthetic_passed'] and v['source_commit']==sha[:12]
    with tempfile.TemporaryDirectory() as t:
        p=Path(t)/'input.json';prepare(p);assert digest(p)==digest(root/'input.json')
    v['input_sha256']=digest(root/'input.json');v['preceding_outputs_sha256']=digest(SOURCE)
    out.mkdir(exist_ok=True,parents=True);(out/'validation.json').write_text(json.dumps(v,indent=2)+'\n')
    (out/'report.md').write_text('# Producer lab and cache parity validation\n\nActual Android compilation and synthetic lab routing passed. Cached, audit-alternative-free producer output matched all 168 frozen preceding predictions. No baseline, old extractor or OCR rerun. This is behavioral parity, not new accuracy evidence.\n')
if __name__=='__main__':
    p=argparse.ArgumentParser();sub=p.add_subparsers(dest='cmd',required=True)
    q=sub.add_parser('prepare');q.add_argument('path',type=Path)
    q=sub.add_parser('collect');q.add_argument('root',type=Path);q.add_argument('output',type=Path);q.add_argument('--sha',required=True)
    a=p.parse_args()
    if a.cmd=='prepare':prepare(a.path)
    else:collect(a.root,a.output,a.sha)
