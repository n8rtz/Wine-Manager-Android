#!/usr/bin/env python3
"""Actual Android hybrid selectors over frozen component outputs; no old extractor runs."""
import argparse
import gzip
import json
from pathlib import Path
from rule_iteration import ROOT,prepare as prepare_baseline,collect as collect_shared
from score_public_corpus import digest
REVISED=ROOT/'tools/evaluation/rule-iteration-39828977215/android-results.jsonl.gz'
VARIANTS=['anchors-only','anchors-role-guard','anchors-role-guard-appellation-1','anchors-role-guard-appellation-2']
DESCRIPTION=('Actual Android/Kotlin hybrid selection over frozen baseline and rules-001 component outputs. '
             'Every image uses baseline producer/vintage and the entire revised wine/geography group; no reference-dependent selection. '
             'Four policies ablate structural abstention on heading-only producers and exact appellation fallback with one/two engines. '
             'No baseline, prior extractor, or OCR rerun. Defaults unchanged; development data only.')
def prepare(path):
    prepare_baseline(path)
    rows=[r for r in map(json.loads,gzip.open(REVISED,'rt')) if r['variant']=='rules-001']
    assert len(rows)==168 and all(r['status']=='complete' for r in rows)
    revised={r['image']:{f:v['value'] for f,v in r['fields'].items()} for r in rows}
    items=json.loads(path.read_text())
    assert set(revised)=={r['image'] for r in items}
    for item in items:item['revised_fields']=revised[item['image']]
    path.write_text(json.dumps(items,ensure_ascii=False))
def collect(root,out,sha):
    inputs={r['image']:r for r in json.loads((root/'input.json').read_text())}
    for line in (root/'results.jsonl').read_text().splitlines():
        row=json.loads(line);item=inputs[row['image']];f=row['fields']
        for field in ('country','region','subregion'):
            assert f[field]['value']==item['revised_fields'][field], 'Hybrid split the origin bundle'
        assert f['vintage']['value']==item['baseline_fields']['vintage']
        assert f['producer']['value'] in ('',item['baseline_fields']['producer'])
        if row['variant']=='anchors-only':assert f['producer']['value']==item['baseline_fields']['producer']
        if 'appellation' not in row['variant']:assert f['wine_name']['value']==item['revised_fields']['wine_name']
    collect_shared(root,out,sha,variants=VARIANTS,description=DESCRIPTION,prepare_input=prepare)
    p=out/'scores.json';result=json.loads(p.read_text())
    result['saved_revised_sha256']=digest(REVISED);result['saved_revised_variant']='rules-001'
    p.write_text(json.dumps(result,indent=2)+'\n')
if __name__=='__main__':
    p=argparse.ArgumentParser();sub=p.add_subparsers(dest='cmd',required=True)
    q=sub.add_parser('prepare');q.add_argument('path',type=Path)
    q=sub.add_parser('collect');q.add_argument('root',type=Path);q.add_argument('output',type=Path);q.add_argument('--sha',required=True)
    a=p.parse_args()
    if a.cmd=='prepare':prepare(a.path)
    else:collect(a.root,a.output,a.sha)
