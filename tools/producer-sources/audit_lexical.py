#!/usr/bin/env python3
"""Read-only lexical diagnostic AFTER source builds; never supplies names to filters."""
import argparse,gzip,hashlib,json,pathlib,unicodedata
ROOT=pathlib.Path(__file__).resolve().parents[2]
def normalize(s):return ''.join(c for c in unicodedata.normalize('NFKD',s.casefold()) if c.isalnum())
def audit(directory):
    ref=ROOT/'tools/evaluation/public-corpus-reference-v1/reference.json'
    known=[r for r in json.loads(ref.read_text())['images'] if r['fields']['producer']['status']=='known']
    result={}
    for p in sorted(pathlib.Path(directory).glob('*.jsonl.gz')):
        with gzip.open(p,'rt',encoding='utf-8') as f:names={normalize(json.loads(line)['name']) for line in f}
        matched=[r for r in known if any(normalize(a) in names for a in r['fields']['producer']['accepted'])]
        result[p.name]=dict(exact_name_photo_matches=len(matched),known_producer_photos=len(known),
            distinct_reference_names=sorted({r['fields']['producer']['value'] for r in matched}),
            reference_sha256=hashlib.sha256(ref.read_bytes()).hexdigest(),input_sha256=hashlib.sha256(p.read_bytes()).hexdigest(),
            note='Exact lexical overlap only; not verified identity coverage or OCR accuracy. No benchmark answers used to build source filters.')
    return result
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--dir',required=True);p.add_argument('--out',required=True);a=p.parse_args()
    pathlib.Path(a.out).write_text(json.dumps(audit(a.dir),ensure_ascii=False,indent=2)+'\n')
