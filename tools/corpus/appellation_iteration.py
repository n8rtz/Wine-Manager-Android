#!/usr/bin/env python3
"""Second Android iteration: exact printed appellation repair, reusing frozen controls."""
import argparse
import json
from pathlib import Path
from rule_iteration import prepare, collect as collect_shared
VARIANTS=[f'appellation-{engines}-{mode}' for mode in ('blank','metadata') for engines in (1,2)]
DESCRIPTION=('Actual Android/Kotlin execution of four baseline repair policies: one vs two co-located OCR engines, '
             'blank wine names only vs also administrative-text wine names. Only exact complete printed appellation forms '
             'compatible with saved country/region/subregion may be used; a visible named cuvée or competing appellations '
             'blocks repair. Other five baseline fields are unchanged. No baseline, prior extractor, or OCR rerun. '
             'These are experimental YELLOW suggestions, not promotion or calibrated probabilities.')
def collect(root,out,sha):
    baseline={r['image']:r['baseline_fields'] for r in json.loads((root/'input.json').read_text())}
    for line in (root/'results.jsonl').read_text().splitlines():
        row=json.loads(line)
        for field,value in baseline[row['image']].items():
            if field!='wine_name':
                assert row['fields'][field]['value']==value, f"Fallback changed protected field: {field}"
    collect_shared(root,out,sha,variants=VARIANTS,description=DESCRIPTION)
if __name__=='__main__':
    p=argparse.ArgumentParser();sub=p.add_subparsers(dest='cmd',required=True)
    q=sub.add_parser('prepare');q.add_argument('path',type=Path)
    q=sub.add_parser('collect');q.add_argument('root',type=Path);q.add_argument('output',type=Path);q.add_argument('--sha',required=True)
    a=p.parse_args()
    if a.cmd=='prepare':prepare(a.path)
    else:collect(a.root,a.output,a.sha)
