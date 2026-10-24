"""Emit independent host-scored fixture for actual Kotlin scoring parity; no OCR."""
import base64,json,sys
from pathlib import Path
from rescore_expected_blanks import classify
from score_public_corpus import normalize
root=Path(__file__).resolve().parents[2]
s=json.loads((root/'app/src/debug/assets/phone-corpus-v1/scoring.json').read_text())
b64=lambda v:base64.b64encode(v.encode()).decode()
for im in s['images']:
    for f,t in im['fields'].items():
        v=s['baseline'][im['image']][f]
        print('\t'.join([im['image'],f,t['status'],b64('|'.join(t['accepted'])),b64(v),classify(t['status'],t,v),b64(normalize(v))]))
