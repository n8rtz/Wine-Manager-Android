#!/usr/bin/env python3
"""Producer-only Android experiment; frozen OCR/control outputs, blank-aware host scoring."""
import argparse,csv,gzip,json,tempfile
from collections import Counter
from pathlib import Path
from replay_saved_evidence import prepare as prepare_ocr
from comprehensive_diagnostics import parse_hypothesis
from rescore_expected_blanks import disposition,classify
from completion_problem_breakdown import completion_gate
from score_public_corpus import digest
ROOT=Path(__file__).resolve().parents[2];E=ROOT/'tools/evaluation'
REF=E/'public-corpus-reference-v1/reference.json'
ANCHOR=E/'anchor-iteration-39834118734/android-results.jsonl.gz'
OLD=E/'saved-evidence-39806541535/android-traces.jsonl.gz'
OCR=E/'public-corpus-run-39716483937/ocr-comparison.csv'
VARIANTS=['producer-direct-2','producer-direct-1','producer-compose-1','producer-consensus-2']
def prepare(path):
    prepare_ocr(path)
    anchors={r['image']:r for r in map(json.loads,gzip.open(ANCHOR,'rt')) if r['variant']=='anchors-role-guard'}
    alts={im:[] for im in anchors}
    for r in map(json.loads,gzip.open(OLD,'rt')):
        for h in r['hypotheses']:
            v=parse_hypothesis(h['values']).get('producer')
            if v:alts[r['image']].append(v)
    with OCR.open() as file:
        for r in csv.DictReader(file):
            if r['stage']=='form_summary':alts[r['image_name']]+=[s.split(': ',1)[0] for s in r['producer_alternatives'].split(' | ') if s]
    items=json.loads(path.read_text());assert len(items)==168 and len(anchors)==168
    for i in items:
        i['anchor_fields']={f:v['value'] for f,v in anchors[i['image']]['fields'].items()}
        i['producer_alternatives']=list(dict.fromkeys(alts[i['image']]))[:64]
    path.write_text(json.dumps(items,ensure_ascii=False))

def collect(root,out,sha,variants=None):
    variants=VARIANTS if variants is None else variants
    validation=json.loads((root/'validation.json').read_text())
    assert validation['images']==168 and set(validation['variants'])==set(variants) and validation['failures']==[]
    assert validation['corpus_budget_ms']==60000 and validation['baseline_executions']==validation['old_experimental_executions']==0
    with tempfile.TemporaryDirectory() as t:
        p=Path(t)/'input.json';prepare(p);assert digest(p)==digest(root/'input.json')
    inputs={r['image']:r for r in json.loads((root/'input.json').read_text())}
    records=[json.loads(line) for line in (root/'results.jsonl').read_text().splitlines()]
    assert len(records)==168*len(variants)
    seen=set()
    for r in records:
        pair=r['variant'],r['image'];assert pair not in seen;seen.add(pair)
        assert r['variant'] in variants and r['image'] in inputs and r['status']=='complete' and r['build']==sha[:12]
        assert set(r['fields'])==set(inputs[r['image']]['anchor_fields'])
        for f,v in r['fields'].items():
            assert isinstance(v['value'],str) and v['state']==('YELLOW' if v['value'] else 'RED')
            if f!='producer':assert v['value']==inputs[r['image']]['anchor_fields'][f],f
    assert digest(REF)==(REF.parent/'reference.sha256').read_text().split()[0]
    references=json.loads(REF.read_text())['images'];byref={r['image']:r for r in references}
    with (E/'blank-aware-rescore-v1/paired-fields.csv').open(encoding='utf-8-sig') as file:fixed=list(csv.DictReader(file))
    outcomes={};outputrows=[]
    for system in ['baseline','previous_v3','guarded_hybrid']:
        outcomes[system]={(r['image'],r['field']):r[system+'_outcome'] for r in fixed}
    for system in variants:
        scores={}
        for row in records:
            if row['variant']!=system:continue
            item=byref[row['image']]
            for f,v in row['fields'].items():
                status,_=disposition(item,f);result=classify(status,item['fields'][f],v['value']);scores[item['image'],f]=result
                outputrows.append(dict(variant=system,review_number=item['review_number'],image=item['image'],field=f,reference_status=status,target=item['fields'][f]['value'] or '',output=v['value'],outcome=result))
        outcomes[system]=scores
    eligible={i['image'] for i in references if all(disposition(i,f)[0]!='unresolved' for f in i['fields'])}
    bad={'wrong_value','unsupported_fill','missing_value'}
    base=outcomes['guarded_hybrid'];basefull={im for im in eligible if all(base[im,f] not in bad for f in byref[im]['fields'])}
    summary=dict(source_commit=sha,input_sha256=digest(root/'input.json'),provenance={str(p.relative_to(ROOT)):digest(p) for p in [REF,ANCHOR,OLD,OCR]},execution=validation,systems={})
    for system,scores in outcomes.items():
        c=Counter(scores.values());p=Counter(v for (im,f),v in scores.items() if f=='producer')
        full={im for im in eligible if all(scores[im,f] not in bad for f in byref[im]['fields'])}
        gate=completion_gate([sum(scores[im,f] in bad for f in byref[im]['fields']) for im in eligible])
        transitions=Counter(base[im,f]+' -> '+v for (im,f),v in scores.items() if f=='producer')
        summary['systems'][system]=dict(outcomes=dict(c),producer=dict(p),completion=gate,
            complete_gained_vs_hybrid=sorted(full-basefull),complete_lost_vs_hybrid=sorted(basefull-full),producer_transitions_vs_hybrid=dict(transitions))
    summary['production_promoted']=False
    decision=dict(meets_user_target=[v for v in variants if summary['systems'][v]['completion']['passes']],production_promoted=False,
        rule='At least 80% complete and strictly fewer than 5% with two or more errors. Component improvements are not release acceptance.')
    out.mkdir(parents=True,exist_ok=True)
    (out/'scores.json').write_text(json.dumps(summary,indent=2)+'\n')
    (out/'decision.json').write_text(json.dumps(decision,indent=2)+'\n')
    with gzip.open(out/'android-results.jsonl.gz','wb') as f:f.write((root/'results.jsonl').read_bytes())
    with (out/'field-results.csv').open('w',newline='') as f:
        w=csv.DictWriter(f,fieldnames=list(outputrows[0]));w.writeheader();w.writerows(outputrows)
    lines=['# Producer-only Android iteration','', 'Saved OCR and all controls reused. Only the new producer stage executed. Other five fields are checked unchanged. Blank-aware references are provisional. No production promotion.','',
        '| System | Producer correct / wrong / missing | Complete forms | Two or more errors | Complete gained / lost vs hybrid | Meets 80% / <5% target |','|---|---:|---:|---:|---:|---|']
    for name,s in summary['systems'].items():
        p=Counter(s['producer']);g=s['completion']
        lines.append(f"| {name} | {p['correct_value']} / {p['wrong_value']+p['unsupported_fill']} / {p['missing_value']} | {g['complete']}/{g['labels']} | {g['two_or_more_errors']} | {len(s['complete_gained_vs_hybrid'])} / {len(s['complete_lost_vs_hybrid'])} | {g['passes']} |")
    lines+=['','Each output includes candidate text, source observation IDs, construction, engine support and a rejection reason, plus the final decision reason. Engine support is ordinal evidence, not a probability. Complete gains/losses and producer transitions are in scores.json.','', 'Only selector execution time is measured; frozen OCR/extractor costs are excluded. These are emulator, not phone timings.','']
    (out/'report.md').write_text('\n'.join(lines))
    return summary
if __name__=='__main__':
    p=argparse.ArgumentParser();sub=p.add_subparsers(dest='cmd',required=True)
    q=sub.add_parser('prepare');q.add_argument('path',type=Path)
    q=sub.add_parser('collect');q.add_argument('root',type=Path);q.add_argument('output',type=Path);q.add_argument('--sha',required=True)
    a=p.parse_args()
    if a.cmd=='prepare':prepare(a.path)
    else:collect(a.root,a.output,a.sha)
