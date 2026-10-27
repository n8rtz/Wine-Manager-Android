#!/usr/bin/env python3
"""Read-only source-name overlap diagnostic against an explicitly supplied reference.
The retired 168-image reference is intentionally not a default or eligible input.
"""
import argparse,gzip,hashlib,json,pathlib,unicodedata
ROOT=pathlib.Path(__file__).resolve().parents[2]
RETIRED=ROOT/'tools/evaluation/public-corpus-reference-v1/reference.json'
RETIRED_REFERENCE_SHA256='8b6fff85ad5b9ec02162bd86dbb8f675294c98680a9d31815441ff871ad7bde2'
def normalize(s):return ''.join(c for c in unicodedata.normalize('NFKD',s.casefold()) if c.isalnum())
def require_nonretired_reference(reference_hash):
    if reference_hash==RETIRED_REFERENCE_SHA256:
        raise ValueError('The retired 168-image reference may not be used for current source coverage audits')
def audit(directory,reference):
    reference=pathlib.Path(reference).resolve()
    reference_bytes=reference.read_bytes()
    reference_hash=hashlib.sha256(reference_bytes).hexdigest()
    require_nonretired_reference(reference_hash)
    ref_data=json.loads(reference_bytes)
    known=[r for r in ref_data['images'] if r['fields']['producer']['status']=='known']
    result={}
    for p in sorted(pathlib.Path(directory).glob('*.jsonl.gz')):
        with gzip.open(p,'rt',encoding='utf-8') as f:names={normalize(json.loads(line)['name']) for line in f}
        matched=[r for r in known if any(normalize(a) in names for a in r['fields']['producer']['accepted'])]
        result[p.name]=dict(exact_name_photo_matches=len(matched),known_producer_photos=len(known),
            distinct_reference_names=sorted({r['fields']['producer']['value'] for r in matched}),
            reference_sha256=reference_hash,input_sha256=hashlib.sha256(p.read_bytes()).hexdigest(),
            note='Exact lexical overlap only; not verified identity coverage or OCR accuracy. Source filters do not read reference answers.')
    return dict(reference_path=str(reference),reference_sha256=reference_hash,layers=result)
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--dir',required=True);p.add_argument('--reference',type=pathlib.Path,required=True);p.add_argument('--out',required=True);a=p.parse_args()
    pathlib.Path(a.out).write_text(json.dumps(audit(a.dir,a.reference),ensure_ascii=False,indent=2)+'\n')
