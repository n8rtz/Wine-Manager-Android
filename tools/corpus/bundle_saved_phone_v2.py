#!/usr/bin/env python3
"""Bundle the user's frozen full phone v2.0 predictions. Never runs OCR or changes targets."""
import csv,hashlib,json
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
SOURCE=ROOT/'tools/evaluation/phone-full-run-28bce9e'
DEST=ROOT/'app/src/debug/assets/phone-corpus-v1/saved-production-v2.json'
def build():
    summary=json.loads((SOURCE/'summary.json').read_text())
    fields={}
    with (SOURCE/'paired-fields.csv').open(encoding='utf-8',newline='') as f:
        for row in csv.DictReader(f):
            values=fields.setdefault(row['image'],{})
            assert row['field'] not in values
            values[row['field']]=row['lab-guarded-producer_output']
    assert len(fields)==168 and all(len(v)==6 for v in fields.values())
    return dict(version=1,label='Production v2.0 — saved full phone run',source_pipeline='lab-guarded-producer',
        source_build=summary['build'],source_csv=summary['source_name'],source_csv_sha256=summary['source_sha256'],
        paired_fields_sha256=hashlib.sha256((SOURCE/'paired-fields.csv').read_bytes()).hexdigest(),
        scoring_sha256=hashlib.sha256((DEST.parent/'scoring.json').read_bytes()).hexdigest(),
        execution_note='168 first attempts; all extraction statuses complete; no engine-incomplete flags. Frozen predictions, not a new OCR run.',
        predictions=fields)
if __name__=='__main__':
    DEST.write_text(json.dumps(build(),ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(DEST)
