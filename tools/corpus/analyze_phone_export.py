#!/usr/bin/env python3
"""Audit a scored phone export. No OCR execution, prediction changes or reference edits."""
import argparse,csv,hashlib,json
from collections import Counter,defaultdict
from pathlib import Path
FIELDS=('producer','wine_name','country','region','subregion','vintage')
PIPELINES=('lab-combined-60s','lab-guarded-producer','lab-trigram','Frozen baseline · same attempted photos')
GOOD={'correct_value','correct_blank'}
def analyze(source,dest):
    rows=list(csv.DictReader(source.open(encoding='utf-8-sig')))
    systems={};indexed={};forms={}
    for name in PIPELINES:
        selected=[r for r in rows if r['row_type']=='field' and r['pipeline']==name]
        index={(r['image'],r['field']):r for r in selected}
        assert len(index)==len(selected)==1008
        indexed[name]=index
        groups=defaultdict(list)
        for r in selected:groups[r['image']].append(r)
        eligible={im:rr for im,rr in groups.items() if all(r['reference_status']!='unresolved' for r in rr)}
        errors={im:[r['field'] for r in rr if r['outcome'] not in GOOD] for im,rr in eligible.items()}
        forms[name]=errors
        systems[name]=dict(images=len(groups),eligible=len(eligible),complete=sum(not x for x in errors.values()),
            one_error=sum(len(x)==1 for x in errors.values()),multi_error=sum(len(x)>1 for x in errors.values()),
            outcomes=dict(Counter(r['outcome'] for r in selected)),
            by_field={f:dict(Counter(r['outcome'] for r in selected if r['field']==f)) for f in FIELDS},
            sole_error_fields=dict(Counter(x[0] for x in errors.values() if len(x)==1)))
        summary=next(r for r in rows if r['row_type']=='summary' and r['Pipeline']==name)
        assert summary['Complete']==f"{systems[name]['complete']}/{len(eligible)}"
        assert int(summary['One error'])==systems[name]['one_error'] and int(summary['2+ errors'])==systems[name]['multi_error']
    guarded=PIPELINES[1];base=PIPELINES[3];g=indexed[guarded]
    paired={}
    for name in (base,PIPELINES[0]):
        paired[name]=dict(complete_gains=[int(g[im,'producer']['review_number']) for im,errs in forms[guarded].items() if not errs and forms[name][im]],
            complete_losses=[int(g[im,'producer']['review_number']) for im,errs in forms[guarded].items() if errs and not forms[name][im]])
    summary=dict(source_name=source.name,source_sha256=hashlib.sha256(source.read_bytes()).hexdigest(),build=rows[0]['build'],
        qualification='Provisional development reference. Scored fields do not establish the raw OCR/extraction root cause. No OCR rerun.',systems=systems,guarded_paired_changes=paired,
        guarded_oracle_upper_bounds=dict(producer_only=sum(set(x)<={'producer'} for x in forms[guarded].values()),
            producer_and_wine_name=sum(set(x)<={'producer','wine_name'} for x in forms[guarded].values())))
    dest.mkdir(parents=True,exist_ok=True)
    (dest/'summary.json').write_text(json.dumps(summary,indent=2,ensure_ascii=False)+'\n')
    compact=[]
    for key,r in sorted(g.items(),key=lambda pair:(int(pair[1]['review_number']),FIELDS.index(pair[1]['field']))):
        record={k:r[k] for k in ('image','review_number','field','reference_status','expected')}
        for name in PIPELINES:
            record[name+'_output']=indexed[name][key]['recognized'];record[name+'_outcome']=indexed[name][key]['outcome']
        compact.append(record)
    with (dest/'paired-fields.csv').open('w',newline='',encoding='utf-8') as f:
        writer=csv.DictWriter(f,fieldnames=list(compact[0]));writer.writeheader();writer.writerows(compact)
    return summary
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('source',type=Path);p.add_argument('destination',type=Path);a=p.parse_args();analyze(a.source,a.destination)
