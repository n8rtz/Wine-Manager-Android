#!/usr/bin/env python3
"""Score actual Android search variants; comparators are frozen outputs, never rerun."""
import argparse
import copy
import csv
import gzip
import json
import math
import statistics
import tempfile
from collections import Counter
from pathlib import Path
from score_public_corpus import load, score, digest, FIELDS
from replay_saved_evidence import prepare
ROOT=Path(__file__).resolve().parents[2]
REF=ROOT/'tools/evaluation/public-corpus-reference-v1'
RUN=ROOT/'tools/evaluation/public-corpus-run-39716483937'
V3=ROOT/'tools/evaluation/saved-evidence-39806541535/android-traces.jsonl.gz'
CONFIGS={'candidates-64-64':(64,64,False,None),'beam-16-256':(16,256,False,None),'wide-64-256':(64,256,False,None),'diverse-16-64':(16,64,True,None),
         'diverse-64-256':(64,256,True,None),'exhaustive-small':(16,64,False,20000)}


def validate(records,validation,expected,sha):
    assert validation['images']==168 and set(validation['variants'])==set(CONFIGS)
    assert validation['baseline_executions']==validation['old_experimental_executions']==0
    assert validation['corpus_budget_ms']==60000 and validation['failures']==[]
    assert len(records)==168*len(CONFIGS)
    seen=set()
    for r in records:
        k=(r['variant'],r['image'])
        assert k not in seen and r['variant'] in CONFIGS and r['image'] in expected
        seen.add(k)
        assert r['build']==sha[:12]
        assert (r['candidate_limit'],r['beam_limit'],r['diversify'],r['exhaustive_cap'])==CONFIGS[r['variant']]
        assert isinstance(r['elapsed_ms'],int) and r['elapsed_ms']>=0
        if r['status']=='skipped_space_limit':
            assert r['variant']=='exhaustive-small' and r['combinations']>20000
            continue
        assert r['status']=='complete',r
        assert set(r['fields'])==set(FIELDS)
        for f in r['fields'].values():
            assert isinstance(f['value'],str) and f['state'] in ('RED','YELLOW','GREEN')
            assert f['state']!='RED' or not f['value']
    assert seen=={(v,i) for v in CONFIGS for i in expected}


def collect(root,out,sha):
    validation=json.loads((root/'validation.json').read_text())
    records=[json.loads(s) for s in (root/'results.jsonl').read_text().splitlines()]
    ref,pred,prod=load(REF,RUN)
    validate(records,validation,set(prod),sha)
    # Ensure the instrumented input was precisely the frozen OCR, not another batch.
    with tempfile.TemporaryDirectory() as d:
        p=Path(d)/'input.json';prepare(p)
        assert digest(p)==digest(root/'input.json'),'Saved OCR input changed'
    old=[json.loads(s) for s in gzip.open(V3,'rt')]
    assert len(old)==168 and {r['image'] for r in old}==set(prod)
    for r in old:pred['experimental'][r['image']]={f:(r['fields'][f]['value'],r['fields'][f]['state']) for f in FIELDS}
    report={'source_commit':sha,'reference_sha256':digest(REF/'reference.json'),
            'input_sha256':digest(root/'input.json'),'saved_v3_sha256':digest(V3),
            'saved_baseline_sha256':digest(RUN/'ocr-comparison.csv'),'execution':validation,'variants':{}}
    allrows=[]
    for variant in CONFIGS:
        runs=[r for r in records if r['variant']==variant]
        complete=[r for r in runs if r['status']=='complete']
        names={r['image'] for r in complete}
        subset=dict(ref,images=[i for i in ref['images'] if i['image'] in names])
        before,beforerows=score(subset,pred,prod)
        revised=copy.deepcopy(pred)
        for r in complete:revised['experimental'][r['image']]={f:(r['fields'][f]['value'],r['fields'][f]['state']) for f in FIELDS}
        after,afterrows=score(subset,revised,prod)
        lookup={(r['image'],r['field']):r for r in beforerows if r['pipeline']=='experimental'}
        transitions=Counter()
        for row in afterrows:
            if row['pipeline']!='experimental':continue
            prev=lookup[row['image'],row['field']]
            transitions[prev['outcome']+' -> '+row['outcome']]+=1
            allrows.append(dict(row,variant=variant,previous_output=prev['output'],previous_outcome=prev['outcome']))
        elapsed=sorted(r['elapsed_ms'] for r in complete)
        counts=Counter()
        for r in complete:counts.update(r['trace_counts'])
        report['variants'][variant]={'completed':len(complete),'skipped':len(runs)-len(complete),
            'eligible_images':sorted(names),'saved_v3_same_subset':before['pipelines']['experimental'],
            'saved_lab_baseline_same_subset':before['pipelines']['lab_baseline'],
            'revised':after['pipelines']['experimental'],'transitions_from_v3':dict(transitions),
            'trace_counts':dict(counts),'elapsed_ms':{'median':statistics.median(elapsed) if elapsed else None,
            'p95':elapsed[math.ceil(.95*len(elapsed))-1] if elapsed else None,'maximum':max(elapsed,default=None)}}
    out.mkdir(parents=True,exist_ok=True)
    (out/'scores.json').write_text(json.dumps(report,indent=2)+'\n')
    with gzip.open(out/'android-traces.jsonl.gz','wb') as f:f.write((root/'results.jsonl').read_bytes())
    with (out/'field-results.csv').open('w',newline='') as f:
        w=csv.DictWriter(f,fieldnames=list(allrows[0]));w.writeheader();w.writerows(allrows)
    lines=['# Experimental search-parameter sweep','',
           'Only new search variants executed in real Android/Kotlin. Frozen OCR, references, lab baseline and v3 outputs reused. Interpretation/ranking rules and ambiguity margin unchanged; all variants allow 60 seconds per case. No production changes.','',
           '| Search | Complete / skipped | Correct / wrong / abstained | Scored fields | All six correct / eligible | V3 correct / wrong / abstained on SAME subset | Median ms |',
           '|---|---:|---:|---:|---:|---:|---:|']
    for name,p in report['variants'].items():
        a=p['revised']['all_fields'];b=p['saved_v3_same_subset']['all_fields'];i=p['revised']['image_results']
        lines.append(f"| {name} | {p['completed']} / {p['skipped']} | {a['correct']} / {a['wrong']} / {a['abstained']} | {a['scored']} | {i['six_field_fully_correct']} / {i['six_field_eligible']} | {b['correct']} / {b['wrong']} / {b['abstained']} | {p['elapsed_ms']['median']} |")
    lines+=['','Diversity groups normalized field values and selects supporting hypotheses round-robin, without summing confidence or erasing source ownership. Different support sets remain alternatives. Exhaustive eligibility is determined without targets: product of (all candidate counts + blank) across groups ≤20,000. Eligible cases have no candidate/beam pruning; oversized cases are explicitly skipped, never counted as abstentions. Exhaustive is only over generated hypotheses, not all possible readings.','',
            'Compare exhaustive results only with the same-subset comparators, not full-corpus totals. scores.json contains per-field counts, identity-deduplicated sensitivity, candidate/beam pruning totals, timing and paired outcome changes. field-results.csv includes every revised output beside the saved v3 result. Wider search is not automatically a win: it can expose genuine ambiguity or amplify bad rankings.','',
            'Single-agent visual references remain provisional. This is development feedback, not held-out accuracy; emulator timing is not phone performance. No variant is automatically promoted.','']
    (out/'report.md').write_text('\n'.join(lines))

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('root',type=Path);p.add_argument('output',type=Path);p.add_argument('--sha',required=True)
    a=p.parse_args();collect(a.root,a.output,a.sha)
