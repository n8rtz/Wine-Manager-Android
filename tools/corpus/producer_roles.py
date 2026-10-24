#!/usr/bin/env python3
"""Explicit maker-role experiment; no added catalogue or spelling corrections."""
import argparse,csv,json
from collections import Counter
from pathlib import Path
from producer_iteration import prepare,collect as collect_shared,E
from score_public_corpus import digest
VARIANTS=['producer-maker-2','producer-maker-1','producer-signature-2','producer-unique-alias-2']
def collect(root,out,sha):
    summary=collect_shared(root,out,sha,variants=VARIANTS)
    previous=E/'producer-refinement-39857230621'
    with (previous/'field-results.csv').open() as f:
        old={(r['image'],r['field']):r for r in csv.DictReader(f) if r['variant']=='producer-refined-2'}
    with (out/'field-results.csv').open() as f: rows=list(csv.DictReader(f))
    bad={'wrong_value','missing_value','unsupported_fill'}
    eligible={im for im,_ in old if all(old[im,f]['reference_status']!='unresolved' for f in ['producer','wine_name','country','region','subregion','vintage'])}
    old_full={im for im in eligible if all(r['outcome'] not in bad for (image,_),r in old.items() if image==im)}
    paired={}
    for variant in VARIANTS:
        current={(r['image'],r['field']):r for r in rows if r['variant']==variant}
        full={im for im in eligible if all(r['outcome'] not in bad for (image,_),r in current.items() if image==im)}
        transitions=Counter(old[k]['outcome']+' -> '+r['outcome'] for k,r in current.items() if k[1]=='producer')
        paired[variant]=dict(complete_gained=sorted(full-old_full),complete_lost=sorted(old_full-full),producer_transitions=dict(transitions),
            newly_wrong_producers=[k[0] for k,r in current.items() if k[1]=='producer' and r['outcome'] in {'wrong_value','unsupported_fill'} and old[k]['outcome'] not in {'wrong_value','unsupported_fill','unscored_filled','unscored_blank'}],
            previously_correct_producers_lost=[k[0] for k,r in current.items() if k[1]=='producer' and old[k]['outcome']=='correct_value' and r['outcome']!='correct_value'])
    result=dict(comparison='frozen producer-refined-2; not rerun',source_sha256=digest(previous/'field-results.csv'),variants=paired)
    (out/'paired-vs-recommended.json').write_text(json.dumps(result,indent=2)+'\n')
    with (out/'report.md').open('a') as f:
        f.write('\n## Paired against the current conservative recommendation (33 complete, 80 multi-error)\n\n')
        f.write('| Policy | Complete gained / lost | Previously correct producers lost | Newly wrong producers |\n|---|---:|---:|---:|\n')
        for variant,v in paired.items():f.write(f"| {variant} | {len(v['complete_gained'])} / {len(v['complete_lost'])} | {len(v['previously_correct_producers_lost'])} | {len(v['newly_wrong_producers'])} |\n")
    return summary
if __name__=='__main__':
    p=argparse.ArgumentParser();sub=p.add_subparsers(dest='cmd',required=True)
    q=sub.add_parser('prepare');q.add_argument('path',type=Path)
    q=sub.add_parser('collect');q.add_argument('root',type=Path);q.add_argument('output',type=Path);q.add_argument('--sha',required=True)
    a=p.parse_args()
    if a.cmd=='prepare':prepare(a.path)
    else:collect(a.root,a.output,a.sha)
