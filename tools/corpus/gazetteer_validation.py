#!/usr/bin/env python3
"""Prepare/collect the actual Android test; no frozen OCR or baseline execution."""
import argparse,json,shutil,sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'tools/gazetteer'))
from build_sqlite import digest,DEFAULT_DB
SOURCE=ROOT/'tools/evaluation/gazetteer-trigram-v1/device-input.json'
def prepare(path):
    path.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(SOURCE,path)
def collect(root,out,sha):
    assert digest(root/'input.json')==digest(SOURCE)
    v=json.loads((root/'validation.json').read_text());cases=json.loads(SOURCE.read_text());positive=sum(c['expected_id'] is not None for c in cases)
    assert v['source_commit']==sha[:12] and v['cases']==len(cases) and v['mismatches']==[]
    assert v['positive_cases']==positive and v['negative_cases']==len(cases)-positive
    assert v['correct']/positive>=.9 and v['false_positives']==0 and v['synthetic_routing_passed']
    assert v['ocr_executions']==v['corpus_baseline_executions']==0
    assert len(v['elapsed_ms'])==len(cases) and all(isinstance(t,(float,int)) and 0<=t<60000 for t in v['elapsed_ms'])
    v.update(input_sha256=digest(SOURCE),database_sha256=digest(DEFAULT_DB))
    out.mkdir(parents=True,exist_ok=True);(out/'validation.json').write_text(json.dumps(v,indent=2)+'\n')
    (out/'decision.json').write_text(json.dumps(dict(synthetic_lexical_target_passed=True,production_promoted=False))+'\n')
    (out/'report.md').write_text(f"# Android trigram verification\n\nActual bundled SQLite trigram probe, Kotlin resolver and synthetic pre-parser routing passed. {v['correct']}/{positive} positive cases correct; zero matches on {v['negative_cases']} negative cases. All {len(cases)} decisions agree with the host reference. No OCR or frozen corpus baseline reruns.\n\nThese are synthetic lexical tests, NOT real-photo or complete-form accuracy. No production promotion.\n")
if __name__=='__main__':
    p=argparse.ArgumentParser();sub=p.add_subparsers(dest='cmd',required=True)
    q=sub.add_parser('prepare');q.add_argument('path',type=Path)
    q=sub.add_parser('collect');q.add_argument('root',type=Path);q.add_argument('out',type=Path);q.add_argument('--sha',required=True)
    a=p.parse_args()
    if a.cmd=='prepare':prepare(a.path)
    else:collect(a.root,a.out,a.sha)
