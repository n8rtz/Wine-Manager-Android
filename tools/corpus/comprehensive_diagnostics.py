#!/usr/bin/env python3
"""Saved-corpus diagnostic and policy ablation suite. Never executes OCR or baseline."""
import argparse
import csv
import gzip
import hashlib
import json
import re
from collections import Counter,defaultdict
from pathlib import Path
from score_public_corpus import load,outcome,normalize,summarize,image_summary,digest,FIELDS
from selection_policies import configs,select,evidence

ROOT=Path(__file__).resolve().parents[2]
REF=ROOT/'tools/evaluation/public-corpus-reference-v1'
RUN=ROOT/'tools/evaluation/public-corpus-run-39716483937'
V3=ROOT/'tools/evaluation/saved-evidence-39806541535/android-traces.jsonl.gz'
P60=ROOT/'tools/evaluation/production-60s-39776810810/production.json'
SWEEP=ROOT/'tools/evaluation/search-sweep-39814991460/scores.json'

def parse_hypothesis(values):
    """Decode this saved Kotlin map format; fail rather than silently mis-score."""
    assert values.startswith('{') and values.endswith('}'),values
    text=values[1:-1]
    matches=list(re.finditer(r'(?:^|, )(PRODUCER|WINE_NAME|COUNTRY|REGION|SUBREGION|VINTAGE)=',text))
    assert matches and matches[0].start()==0,values
    result={}
    for i,m in enumerate(matches):
        key=m.group(1).lower();assert key not in result
        result[key]=text[m.end():matches[i+1].start() if i+1<len(matches) else len(text)]
    return result

def matches(target,values):
    return target['status']=='known' and any(outcome(target,v)=='correct' for v in values)

def rows_for(items,pred):
    return [dict(image=i['image'],review_number=i['review_number'],identity_group=i['identity_group'],field=f,
                 target=i['fields'][f]['value'] or '',output=pred[i['image']][f],outcome=outcome(i['fields'][f],pred[i['image']][f]))
            for i in items for f in FIELDS]

def metrics(items,pred,baseline):
    rows=rows_for(items,pred);by_image=defaultdict(list)
    for r in rows:by_image[r['image']].append(r)
    eligible={i['image'] for i in items if all(i['fields'][f]['status']=='known' for f in FIELDS)}
    full={name for name,rs in by_image.items() if name in eligible and all(r['outcome']=='correct' for r in rs)}
    basefull={i['image'] for i in items if i['image'] in eligible and all(outcome(i['fields'][f],baseline[i['image']][f])=='correct' for f in FIELDS)}
    representatives={}
    for i in items:representatives.setdefault(i['identity_group'],i['image'])
    dedup=[r for r in rows if r['image'] in representatives.values()]
    folds={}
    for k in range(5):
        sample=[r for r in rows if int(hashlib.sha256(r['identity_group'].encode()).hexdigest(),16)%5==k]
        folds[str(k)]=dict(fields=summarize(sample),images=image_summary(sample))
    return dict(fields=summarize(rows),per_field={f:summarize([r for r in rows if r['field']==f]) for f in FIELDS},
                images=image_summary(rows),complete_gained=sorted(full-basefull),complete_lost=sorted(basefull-full),
                one_per_identity=dict(fields=summarize(dedup),images=image_summary(dedup)),
                identity_group_slices=folds,
                structural_flags=dict(subregion_without_region=sum(bool(p['subregion']) and not p['region'] for p in pred.values()),
                    identical_producer_wine=sum(bool(p['producer']) and normalize(p['producer'])==normalize(p['wine_name']) for p in pred.values())))

def run(output):
    ref,pred,production=load(REF,RUN);items=ref['images'];names={i['image'] for i in items}
    traces={r['image']:r for r in (json.loads(line) for line in gzip.open(V3,'rt'))}
    assert set(traces)==names
    systems={name:{im:{f:v[0] for f,v in fields.items()} for im,fields in entries.items()} for name,entries in pred.items()}
    systems['experimental_v2']=systems.pop('experimental')
    systems['experimental_v3']={im:{f:r['fields'][f]['value'] for f in FIELDS} for im,r in traces.items()}
    p60=json.loads(P60.read_text());assert set(p60)==names
    systems['production_60s']={im:{f:r['fields'][f.upper()]['value'] for f in FIELDS} for im,r in p60.items()}
    baseline=systems['lab_baseline'];v3=systems['experimental_v3']
    readings=defaultdict(list);alternatives=defaultdict(lambda:defaultdict(list));hypotheses=defaultdict(lambda:defaultdict(list))
    with (RUN/'ocr-comparison.csv').open() as file:
        for r in csv.DictReader(file):
            if r['stage']=='recognition' and r['status'] in ('complete','greedy_fallback') and r['raw_text']:
                readings[r['image_name']].append(r)
            if r['stage']=='form_summary':
                for f in FIELDS:
                    alternatives[r['image_name']][f]=[s.split(': ',1)[0] for s in r[f+'_alternatives'].split(' | ') if s]
    for im,r in traces.items():
        for h in r['hypotheses']:
            for f,v in parse_hypothesis(h['values']).items():hypotheses[im][f].append(v)
    diagnostic=[];case_rows=[];stage_counts=Counter();oracle_whole=0;oracle_fields=0;oracle_pools=0
    for item in items:
        im=item['image'];eligible=all(item['fields'][f]['status']=='known' for f in FIELDS)
        a={f:outcome(item['fields'][f],baseline[im][f]) for f in FIELDS}
        b={f:outcome(item['fields'][f],v3[im][f]) for f in FIELDS}
        whole=eligible and (all(v=='correct' for v in a.values()) or all(v=='correct' for v in b.values()))
        field_union=eligible and all(a[f]=='correct' or b[f]=='correct' for f in FIELDS)
        pool_union=eligible and all(matches(item['fields'][f],[baseline[im][f],v3[im][f]]+alternatives[im][f]+hypotheses[im][f]) for f in FIELDS)
        oracle_whole+=whole;oracle_fields+=field_union;oracle_pools+=pool_union
        case_rows.append(dict(review_number=item['review_number'],image=im,identity_group=item['identity_group'],eligible=eligible,
            baseline_fields_remaining=sum(v in ('wrong','abstained') for v in a.values()),v3_fields_remaining=sum(v in ('wrong','abstained') for v in b.values()),
            baseline_missing=';'.join(f for f in FIELDS if a[f] in ('wrong','abstained')),v3_missing=';'.join(f for f in FIELDS if b[f] in ('wrong','abstained')),
            oracle_either_whole=whole,oracle_field_union=field_union,oracle_candidate_union=pool_union))
        for f in FIELDS:
            target=item['fields'][f]
            witnesses=[]
            if target['status']=='known' and f in ('producer','wine_name','vintage'):
                witnesses=[dict(engine=r['candidate'],text=r['raw_text'],mlkit_confidence=r['mlkit_confidence'],
                    ctc_posterior=r['ctc_posterior'],confidence_estimated=r['confidence_estimated'],box=r['base_box_normalized'])
                    for r in readings[im] if any(normalize(v) and normalize(v) in normalize(r['raw_text']) for v in target['accepted'])]
            if target['status']=='unknown':stage='unscored_reference'
            elif b[f]=='correct':stage='v3_selected_correct'
            elif matches(target,hypotheses[im][f]):stage='correct_v3_hypothesis_not_selected'
            elif a[f]=='correct':stage='baseline_selected_correct_v3_candidate_missing'
            elif matches(target,alternatives[im][f]):stage='correct_baseline_alternative_not_selected'
            elif f in ('country','region','subregion'):stage='geography_needs_semantic_origin_review'
            else:
                # Literal containment is a coverage clue only: aliases/normalization/role
                # errors can cause both false positives and false negatives.
                stage='literal_text_present_candidate_missing' if witnesses else 'no_literal_match_not_proof_of_ocr_failure'
            stage_counts[stage]+=1
            diagnostic.append(dict(review_number=item['review_number'],image=im,field=f,target=target['value'] or '',basis=target['basis'],
                baseline=baseline[im][f],v3=v3[im][f],baseline_outcome=a[f],v3_outcome=b[f],stage=stage,
                literal_witnesses=json.dumps(witnesses,ensure_ascii=False),literal_engines=';'.join(sorted({w['engine'] for w in witnesses})),
                baseline_alternatives=json.dumps(alternatives[im][f],ensure_ascii=False),v3_hypotheses=json.dumps(hypotheses[im][f],ensure_ascii=False)))
    summary={'schema_version':1,'policy_count':len(configs()),'inputs':{str(p.relative_to(ROOT)):digest(p) for p in (REF/'reference.json',RUN/'ocr-comparison.csv',RUN/'production.json',V3,P60,SWEEP)},
        'implementation':{str(p.relative_to(ROOT)):digest(p) for p in (Path(__file__),ROOT/'tools/corpus/selection_policies.py')},
        'systems':{s:metrics(items,p,baseline) for s,p in systems.items()},
        'reference_assisted_upper_bounds':dict(eligible=sum(c['eligible'] for c in case_rows),either_whole_entry=oracle_whole,selected_field_union=oracle_fields,candidate_pool_union=oracle_pools,
            warning='Reference-assisted opportunity diagnostics only, NOT deployable accuracy; candidate pool union ignores cross-field incompatibilities.'),
        'stage_counts':dict(stage_counts),
        'stage_counts_by_field':{f:dict(Counter(r['stage'] for r in diagnostic if r['field']==f)) for f in FIELDS},
        'literal_engine_coverage_proxy':{f:dict(Counter(r['literal_engines'] or 'none' for r in diagnostic if r['field']==f and r['stage']!='unscored_reference')) for f in ('producer','wine_name','vintage')},'policies':{}}
    # Critical separation: prediction generation receives no item fields or targets.
    policy_outputs={};policy_decisions={}
    for config in configs():
        outputs={};owners={}
        for im in sorted(names):outputs[im],owners[im]=select(config,baseline[im],v3[im],readings[im])
        policy_outputs[config['id']]=outputs
        policy_decisions[config['id']]=owners
        stats=metrics(items,outputs,baseline);stats['config']=config
        stats['mixed_geography_sources']=sum(len({owners[im][f] for f in ('country','region','subregion') if outputs[im][f]})>1 for im in names)
        summary['policies'][config['id']]=stats
    safe=[(name,s) for name,s in summary['policies'].items() if s['config']['kind']!='fields'
          and s['fields']['wrong']<=summary['systems']['lab_baseline']['fields']['wrong']]
    summary['safer_shortlist']= [name for name,s in sorted(safe,key=lambda kv:(-kv[1]['images']['six_field_fully_correct'],kv[1]['fields']['wrong'],kv[0]))[:10]]
    summary['near_complete_eligible']={system:dict(Counter(str(c[system+'_fields_remaining']) for c in case_rows if c['eligible'])) for system in ('baseline','v3')}
    # No learned thresholds or automatic winner promotion; this is an exploratory grid.
    output.mkdir(parents=True,exist_ok=True)
    (output/'summary.json').write_text(json.dumps(summary,indent=2,ensure_ascii=False)+'\n')
    def csvwrite(path,rows):
        with path.open('w',newline='') as file:
            writer=csv.DictWriter(file,fieldnames=list(rows[0]));writer.writeheader();writer.writerows(rows)
    csvwrite(output/'field-diagnostics.csv',diagnostic);csvwrite(output/'case-diagnostics.csv',case_rows)
    with gzip.open(output/'policy-outputs.json.gz','wt') as file:json.dump(policy_outputs,file,ensure_ascii=False)
    with gzip.open(output/'policy-decisions.json.gz','wt') as file:
        json.dump(dict(owners=policy_decisions,source_legend={'0':'lab_baseline','1':'experimental_v3','-1':'abstained'},
                       evidence_scores={im:{f:[evidence(baseline[im][f],f,readings[im]),evidence(v3[im][f],f,readings[im])] for f in FIELDS} for im in sorted(names)}),file)
    ranking=sorted(summary['policies'].items(),key=lambda kv:(-kv[1]['images']['six_field_fully_correct'],kv[1]['fields']['wrong'],-kv[1]['fields']['correct'],kv[0]))
    lines=['# Comprehensive saved-corpus diagnostic suite','',
        '**Exploratory development-set analysis—not held-out accuracy or an Android implementation of a hybrid.** No OCR, baseline, v2 or v3 extractor was rerun. All selection policies operate on saved outputs only; production unchanged.','',
        '## Existing workflow components','', '| Saved system | Correct / wrong / abstained | Complete / eligible |','|---|---:|---:|']
    for name,s in summary['systems'].items():
        f=s['fields'];i=s['images'];lines.append(f"| {name} | {f['correct']} / {f['wrong']} / {f['abstained']} | {i['six_field_fully_correct']} / {i['six_field_eligible']} |")
    lines+=['','## Opportunity bounds (use reference answers; NOT runtime policies)','',f'- Either complete entry already correct: **{oracle_whole}/103**.',f'- Every field correct in at least one selected output: **{oracle_fields}/103**.',f'- Every field correct somewhere in selected outputs / baseline alternatives / v3 hypotheses: **{oracle_pools}/103**.',
        'The latter two can require incompatible choices. They locate potential opportunity, not an achievable guarantee. Candidate traces are limited to what was saved.','',
        '## Stage triage','', '| Category | Fields |','|---|---:|']
    for name,n in sorted(stage_counts.items()):lines.append(f'| {name} | {n} |')
    lines+=['','Literal-text checks are proxies, not proof of OCR correctness/failure. Geography is not classified from literal absence because it can require appellation inference. A correct generated hypothesis not selected does not distinguish rule rejection, search pruning, ranking and ambiguity; inspect Android decision traces before claiming causation.','',
        f'## {len(configs())} reference-free selection policies','',
        '16 coherent producer/name/geography/vintage source assignments; 64 independent-field source assignments (stress tests); 8 preferred-source/fallback/disagreement policies; 18 OCR-evidence score/margin policies; strict agreement. Raw OCR support is not independent model consensus: both extractors share evidence. No probabilities are manufactured.','',
        'Top 12 **in-sample** policies below, ranked by full entries then wrong fills. These are hypotheses to validate, not recommendations to deploy. Full grid, per-field results, gained/lost complete entries, duplicate sensitivity, structural flags and deterministic identity-group slices are in summary.json. Group slices are robustness diagnostics, NOT a held-out test: the corpus has already informed development.','',
        '| Policy | Complete / eligible | Correct / wrong / abstained | Complete gained / lost vs baseline | Mixed geography sources |','|---|---:|---:|---:|---:|']
    for name,s in ranking[:12]:
        f=s['fields'];i=s['images'];lines.append(f"| {name} | {i['six_field_fully_correct']} / {i['six_field_eligible']} | {f['correct']} / {f['wrong']} / {f['abstained']} | {len(s['complete_gained'])} / {len(s['complete_lost'])} | {s['mixed_geography_sources']} |")
    lines+=['','## Near-complete entries','',
        'Number of unresolved known fields on the 103 six-known photos (wrong fills and blanks both count):','']
    for system,counts in summary['near_complete_eligible'].items():
        lines.append(f'- {system}: '+', '.join(f'{n} fields remaining: {counts[n]} photos' for n in sorted(counts,key=int)))
    lines+=['','## Lower-error shortlist (not automatically safe to deploy)','',
        'These group-preserving policies have no more total wrong fills than the saved baseline. Complete-entry gains and losses still need inspection; a lower error total does not mean zero new errors.','']
    for name in summary['safer_shortlist'][:5]:
        s=summary['policies'][name];f=s['fields']
        lines.append(f"- `{name}`: {s['images']['six_field_fully_correct']}/103 complete; {f['correct']} correct, {f['wrong']} wrong, {f['abstained']} abstained; {len(s['complete_gained'])} complete entries gained and {len(s['complete_lost'])} lost.")
    lines+=['','## Review and reproduction','',
        '- `case-diagnostics.csv`: eligible near-complete entries, blocking fields and oracle opportunity flags.',
        '- `field-diagnostics.csv`: all 1008 targets, outputs, provenance, saved alternatives and stage triage.',
        '- `policy-outputs.json.gz`: every policy output, allowing independent rescoring without any extractor rerun.',
        '- `policy-decisions.json.gz`: selected source per field and source evidence scores; -1 means abstention, not a newly generated value.',
        '- `summary.json`: complete grid, hashes, per-field scores, unknowns, complete-entry regressions, group slices and duplicate sensitivity.',
        '- Existing Android search ablation: `tools/evaluation/search-sweep-39814991460/`. Its exhaustive run supplies independent evidence that widening search alone recovered no correct fields on this corpus.',
        '- Missing-region/duplicate-name/mixed-geography flags do not establish full semantic coherence. No KB hierarchy is rerun or guessed here.',
        '- Compare production and lab paths cautiously: image processing and budgets differ. Saved evidence does not isolate each preprocessing stage.',
        '- Freeze references; adjudicate disputed roles separately and apply any revised scoring equally. Validate promising hybrid policies in actual Kotlin and on fresh labels before promotion.','',
        '```sh','python3 tools/corpus/comprehensive_diagnostics.py','python3 -m unittest discover -s tools/tests','```','']
    (output/'report.md').write_text('\n'.join(lines))
    print(json.dumps(summary['reference_assisted_upper_bounds'],indent=2))
    print('Policies:',len(configs()),'Best exploratory:',ranking[0][0],ranking[0][1]['images'])

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--output',type=Path,default=ROOT/'tools/evaluation/comprehensive-diagnostics-v1')
    args=p.parse_args();run(args.output)
