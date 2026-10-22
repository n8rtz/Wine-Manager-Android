#!/usr/bin/env python3
"""Prepare actual Android rule experiments; select advancement only after paired scoring."""
import argparse
import copy
import csv
import gzip
import json
import tempfile
from collections import Counter
from pathlib import Path
from replay_saved_evidence import prepare as prepare_ocr
from score_public_corpus import load,score,digest,FIELDS
from score_search_sweep import REF,RUN,V3
ROOT=Path(__file__).resolve().parents[2]
VARIANTS=[f'rules-{n:03b}' for n in range(1,8)]+['baseline-repair','baseline-cuvee-repair']

def prepare(path):
    prepare_ocr(path)
    items=json.loads(path.read_text());_,pred,_=load(REF,RUN)
    for item in items:item['baseline_fields']={f:pred['lab_baseline'][item['image']][f][0] for f in FIELDS}
    path.write_text(json.dumps(items,ensure_ascii=False))

def collect(root,out,sha,variants=None,description=None,prepare_input=None):
    variants=VARIANTS if variants is None else variants
    prepare_input=prepare if prepare_input is None else prepare_input
    validation=json.loads((root/'validation.json').read_text())
    assert validation['images']==168 and set(validation['variants'])==set(variants)
    assert validation['failures']==[] and validation['corpus_budget_ms']==60000
    assert validation['baseline_executions']==validation['old_experimental_executions']==0
    with tempfile.TemporaryDirectory() as d:
        p=Path(d)/'input.json';prepare_input(p);assert digest(p)==digest(root/'input.json')
    ref,pred,prod=load(REF,RUN)
    old=[json.loads(line) for line in gzip.open(V3,'rt')]
    assert len(old)==168 and {r['image'] for r in old}==set(prod)
    for r in old:pred['experimental'][r['image']]={f:(r['fields'][f]['value'],r['fields'][f]['state']) for f in FIELDS}
    prior,priorrows=score(ref,pred,prod)
    records=[json.loads(line) for line in (root/'results.jsonl').read_text().splitlines()]
    assert len(records)==168*len(variants)
    seen=set()
    for r in records:
        key=r['variant'],r['image'];assert key not in seen;seen.add(key)
        assert r['variant'] in variants and r['image'] in prod and r['status']=='complete' and r['build']==sha[:12]
        assert set(r['fields'])==set(FIELDS)
        for f in r['fields'].values():
            assert isinstance(f['value'],str) and f['state'] in ('RED','YELLOW')
            assert (not f['value'])==(f['state']=='RED')
    result={'source_commit':sha,'reference_sha256':digest(REF/'reference.json'),'input_sha256':digest(root/'input.json'),
            'saved_v3_sha256':digest(V3),'saved_baseline_sha256':digest(RUN/'ocr-comparison.csv'),
            'execution':validation,'v3':prior['pipelines']['experimental'],'baseline':prior['pipelines']['lab_baseline'],'variants':{}}
    outputrows=[]
    for name in variants:
        predicted=copy.deepcopy(pred)
        for r in records:
            if r['variant']==name:predicted['experimental'][r['image']]={f:(r['fields'][f]['value'],r['fields'][f]['state']) for f in FIELDS}
        metrics,rows=score(ref,predicted,prod)
        p=metrics['pipelines']['experimental'];p['paired']={}
        for source in ('lab_baseline','experimental'):
            lookup={(r['image'],r['field']):r for r in priorrows if r['pipeline']==source}
            transitions=Counter()
            old_full={im for im in prod if all(lookup[im,f]['outcome']=='correct' for f in FIELDS)}
            new_lookup={(r['image'],r['field']):r for r in rows if r['pipeline']=='experimental'}
            new_full={im for im in prod if all(new_lookup[im,f]['outcome']=='correct' for f in FIELDS)}
            for key,r in new_lookup.items():transitions[lookup[key]['outcome']+' -> '+r['outcome']]+=1
            p['paired'][source]=dict(transitions=dict(transitions),complete_gained=sorted(new_full-old_full),complete_lost=sorted(old_full-new_full))
        result['variants'][name]=p
        outputrows += [dict(r,variant=name) for r in rows if r['pipeline']=='experimental']
    # Predeclared advancement gate: choose for further experiment, NEVER deployment.
    base=result['baseline'];v3=result['v3']
    eligible=[n for n,p in result['variants'].items() if p['image_results']['six_field_fully_correct']>max(base['image_results']['six_field_fully_correct'],v3['image_results']['six_field_fully_correct'])
        and p['all_fields']['wrong']<=v3['all_fields']['wrong'] and p['all_fields']['correct']>=base['all_fields']['correct']
        and p['one_photo_per_identity']['image_results']['six_field_fully_correct']>=base['one_photo_per_identity']['image_results']['six_field_fully_correct']]
    result['decision']=dict(advance_candidates=eligible,production_promoted=False,
        rule='Advance for further testing only if full entries exceed both controls, wrong fills <= v3, correct fields >= baseline, deduplicated full entries >= baseline.',
        next_action='Validate candidate on fresh grouped labels and Android regression suite.' if eligible else 'Do not promote. Use paired losses and hypothesis traces to target the next general rule change.')
    out.mkdir(parents=True,exist_ok=True)
    (out/'scores.json').write_text(json.dumps(result,indent=2)+'\n')
    (out/'decision.json').write_text(json.dumps(result['decision'],indent=2)+'\n')
    with gzip.open(out/'android-results.jsonl.gz','wb') as f:f.write((root/'results.jsonl').read_bytes())
    with (out/'field-results.csv').open('w',newline='') as f:
        w=csv.DictWriter(f,fieldnames=list(outputrows[0]));w.writeheader();w.writerows(outputrows)
    lines=['# Corpus-guided rule/architecture iteration','',
        description or 'Actual Android/Kotlin execution; seven rule combinations plus two targeted baseline correction strategies. Saved OCR and all old comparisons reused. No production change.','',
        '| Variant | Correct / wrong / abstained | Complete / eligible | Complete gained / lost vs baseline |','|---|---:|---:|---:|']
    for name,p in [('saved baseline',base),('saved v3',v3)]+list(result['variants'].items()):
        f=p['all_fields'];i=p['image_results'];pair=p.get('paired',{}).get('lab_baseline',{})
        paired_display="—" if name=="saved v3" else f"{len(pair.get('complete_gained',[]))} / {len(pair.get('complete_lost',[]))}"
        lines.append(f"| {name} | {f['correct']} / {f['wrong']} / {f['abstained']} | {i['six_field_fully_correct']} / {i['six_field_eligible']} | {paired_display} |")
    lines+=['',description and 'Each policy is evaluated on all 168 saved cases; no reference answers enter Android.' or 'Rules bits: equivalence of name typography / independent-engine name agreement / explicit cuvée role. Rules-000 is the saved v3 result and is not rerun. The two hybrid strategies use rules-111 results with saved baseline fields, preserving baseline geography and vintage. They require two-engine direct support for changes; a stricter variant only repairs missing/head-only identities, while the other can replace an exact appellation wine name with an explicitly named cuvée.','',
        '## Automatic next-step gate','',json.dumps(result['decision'],indent=2),'',
        'This is a development-set result, not held-out validation. Reference judgments remain provisional. The gate selects further testing only; it never changes production or claims a cure.',
        'Hybrid elapsed times in raw output reuse the shared extraction time, not an additional baseline execution.' if description is None else
        'Elapsed times cover only the new Kotlin selection/repair logic, excluding frozen component extraction and OCR. These are emulator timings, not phone performance.','']
    (out/'report.md').write_text('\n'.join(lines))

if __name__=='__main__':
    p=argparse.ArgumentParser();sub=p.add_subparsers(dest='cmd',required=True)
    q=sub.add_parser('prepare');q.add_argument('path',type=Path)
    q=sub.add_parser('collect');q.add_argument('root',type=Path);q.add_argument('output',type=Path);q.add_argument('--sha',required=True)
    a=p.parse_args()
    if a.cmd=='prepare':prepare(a.path)
    else:collect(a.root,a.output,a.sha)
