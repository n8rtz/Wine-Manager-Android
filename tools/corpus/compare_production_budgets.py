#!/usr/bin/env python3
"""Compare a verified 60s production replay with frozen 15s outputs/targets."""
import argparse
import json
import statistics
from pathlib import Path
from score_public_corpus import load, score, digest

ROOT = Path(__file__).resolve().parents[2]


def compare(run, output):
    refdir = ROOT/'tools/evaluation/public-corpus-reference-v1'
    olddir = ROOT/'tools/evaluation/public-corpus-run-39716483937'
    ref, oldpred, oldprod = load(refdir, olddir)
    newref, newpred, newprod = load(refdir, run)
    provenance = json.loads((run/'budget-build.json').read_text())
    assert provenance['production_budget_ms'] == 60000
    assert provenance['baseline_budget_ms'] == 15000
    old, oldrows = score(ref, oldpred, oldprod)
    new, newrows = score(newref, newpred, newprod)
    def metrics(result, prod):
        times = sorted(r['elapsedMs'] for r in prod.values())
        return dict(fields=result['pipelines']['production']['all_fields'],
                    per_field=result['pipelines']['production']['per_field'],
                    images=result['pipelines']['production']['image_results'],
                    one_photo_per_identity=result['pipelines']['production']['one_photo_per_identity'],
                    deadline_cancelled=sum(r['deadlineCancelled'] for r in prod.values()),
                    elapsed_ms=dict(median=statistics.median(times), p95=times[159], maximum=max(times)))
    before={(r['image'],r['field']):r['outcome'] for r in oldrows if r['pipeline']=='production'}
    transitions={}
    for r in newrows:
        if r['pipeline']!='production': continue
        key=before[(r['image'],r['field'])]+' -> '+r['outcome']
        transitions[key]=transitions.get(key,0)+1
    data=dict(baseline_15s=metrics(old,oldprod), comparison_60s=metrics(new,newprod),
              field_transitions=transitions, build=provenance,
              reference_sha256=digest(refdir/'reference.json'),
              baseline_production_sha256=digest(olddir/'production.json'),
              comparison_production_sha256=digest(run/'production.json'))
    output.mkdir(parents=True,exist_ok=True)
    (output/'comparison.json').write_text(json.dumps(data,indent=2)+'\n')
    lines=['# Production 15s versus 60s shared budget','',
           'Same frozen 168-photo reference and production logic. Only the shared deadline constant is changed in the ephemeral CI build; normal app source remains at 15 seconds. Lab reruns are not used as the comparator.','',
           '| Budget | Correct | Wrong | Abstained | Unknown | All six correct / eligible | Deadlines | Median ms | p95 ms |',
           '|---|---:|---:|---:|---:|---:|---:|---:|---:|']
    for label,key in [('15 seconds','baseline_15s'),('60 seconds','comparison_60s')]:
        m=data[key];f=m['fields'];i=m['images'];t=m['elapsed_ms']
        lines.append(f"| {label} | {f['correct']} | {f['wrong']} | {f['abstained']} | {f['unscored']} | {i['six_field_fully_correct']} / {i['six_field_eligible']} | {m['deadline_cancelled']} | {t['median']} | {t['p95']} |")
    lines+=['','The 114 unknown fields are not rewarded as correct blanks. References are single-agent visual review, not expert-adjudicated truth. This is a historical-run comparison, not randomized paired timing on the same runner. Emulator elapsed times are not phone performance; a phone waiting-time/battery check is still needed.','',
            'See comparison.json for per-field results, identity-deduplicated sensitivity, outcome transitions, input hashes, and the exact CI-only build modification.','']
    (output/'comparison.md').write_text('\n'.join(lines))

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('run',type=Path);p.add_argument('output',type=Path)
    a=p.parse_args();compare(a.run,a.output)
