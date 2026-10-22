#!/usr/bin/env python3
"""Separate observed field failures and reference-assisted opportunities; no extractor runs."""
import csv,gzip,json
from collections import Counter,defaultdict
from fractions import Fraction
from pathlib import Path
from score_public_corpus import normalize
ROOT=Path(__file__).resolve().parents[2]
E=ROOT/'tools/evaluation'
OUT=E/'completion-problem-breakdown-v1'
ERRORS={'wrong_value','missing_value','unsupported_fill'}

def completion_gate(error_counts):
    """User target: >=80% complete, strictly <5% with >=2 errors; blanks required are errors."""
    counts=list(error_counts);n=len(counts)
    if not n:raise ValueError('No fully scorable labels')
    if any(not isinstance(c,int) or c<0 or c>6 for c in counts):raise ValueError('Invalid error count')
    complete=counts.count(0);multiple=sum(c>=2 for c in counts)
    return dict(labels=n,complete=complete,two_or_more_errors=multiple,
        passes=Fraction(complete,n)>=Fraction(4,5) and Fraction(multiple,n)<Fraction(1,20),
        minimum_complete=(4*n+4)//5,maximum_two_or_more_errors=(n-1)//20)

def run():
    OUT.mkdir(exist_ok=True)
    rows=list(csv.DictReader((E/'blank-aware-rescore-v1/paired-fields.csv').open(encoding='utf-8-sig')))
    labels=list(csv.DictReader((E/'blank-aware-rescore-v1/paired-labels.csv').open(encoding='utf-8-sig')))
    eligible={r['image'] for r in labels if r['fully_scorable']=='True'}
    references={r['image']:r for r in json.loads((E/'public-corpus-reference-v1/reference.json').read_text())['images']}
    diag={(r['image'],r['field']):r for r in csv.DictReader((E/'comprehensive-diagnostics-v1/field-diagnostics.csv').open())}
    revised={r['image']:r for r in map(json.loads,gzip.open(E/'rule-iteration-39828977215/android-results.jsonl.gz','rt')) if r['variant']=='rules-001'}
    errors=defaultdict(set);cases=[];totals=defaultdict(Counter);stages=defaultdict(Counter)
    for r in rows:
        im=r['image'];f=r['field'];result=r['guarded_hybrid_outcome']
        totals[f][result]+=1
        if result not in ERRORS:continue
        if im in eligible:errors[im].add(f)
        d=diag[im,f];target=references[im]['fields'][f]
        accepted={normalize(v) for v in target['accepted']}
        def matches(values):return any(v and normalize(v) in accepted for v in values)
        if r['new_reference_status']=='expected_blank':stage='unsupported_fill_expected_blank'
        elif matches([d['baseline'],revised[im]['fields'][f]['value']]):stage='correct_value_in_saved_component_output'
        elif matches(json.loads(d['baseline_alternatives'])+json.loads(d['v3_hypotheses'])):stage='correct_value_only_in_saved_candidates'
        elif json.loads(d['literal_witnesses']):stage='literal_witness_without_correct_saved_candidate'
        elif f in ('country','region','subregion'):stage='geographic_evidence_or_path_needs_review'
        else:stage='no_exact_saved_witness_cause_not_established'
        stages[f][stage]+=1
        cases.append(dict(review_number=r['review_number'],image=im,field=f,outcome=result,triage=stage,
            expected_status=r['new_reference_status'],target=r['target'],output=r['guarded_hybrid_output'],
            baseline=d['baseline'],revised_component=revised[im]['fields'][f]['value'],
            baseline_alternatives=d['baseline_alternatives'],old_v3_hypotheses=d['v3_hypotheses'],literal_witnesses=d['literal_witnesses'],notes=r['review_notes']))
    def write(name,data):
        with (OUT/name).open('w',encoding='utf-8-sig',newline='') as file:
            w=csv.DictWriter(file,fieldnames=list(data[0]));w.writeheader();w.writerows(data)
    write('failure-cases.csv',sorted(cases,key=lambda r:(r['field'],int(r['review_number']))))
    table=[dict(field=f,missing_required_values=c['missing_value'],wrong_values=c['wrong_value'],unsupported_fills=c['unsupported_fill'],total_problems=sum(c[k] for k in ERRORS)) for f,c in totals.items()]
    table.sort(key=lambda r:r['total_problems'],reverse=True);write('field-priorities.csv',table)
    scenarios=[]
    for fixes in [[],['producer'],['wine_name'],['country'],['region'],['subregion'],['producer','wine_name'],['producer','wine_name','country'],['producer','wine_name','region','subregion']]:
        metrics=completion_gate([len(errors[im]-set(fixes)) for im in eligible]);scenarios.append(dict(assumed_perfect_fields=fixes,**metrics))
    result=dict(actual=completion_gate([len(errors[im]) for im in eligible]),unscorable_labels=168-len(eligible),
        field_priorities=table,opportunity_triage={f:dict(c) for f,c in stages.items()},oracle_scenarios=scenarios,
        cautions=['Oracle scenarios replace errors using reference answers; they are not predicted attainable results.',
                  'Candidate coverage uses historical saved outputs/alternatives. It does not prove runtime compatibility or isolate ranking versus generation failure.',
                  'Literal witnesses are normalized text-match proxies, not proof of correct OCR; absent witnesses do not establish OCR failure.',
                  'All results remain provisional development-set results, not fresh held-out validation.'])
    (OUT/'summary.json').write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps(result,indent=2))
    return result
if __name__=='__main__':run()
