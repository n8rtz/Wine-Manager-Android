#!/usr/bin/env python3
"""Compare admitted primary producer names to the earlier pinned derivative by LWIN7.
No benchmark answers; the mirror never supplies or repairs primary names.
"""
import argparse,hashlib,json,pathlib,sqlite3
from prepare_primary import normalize

def compare(asset,mirror,out):
    asset=pathlib.Path(asset);mirror=pathlib.Path(mirror)
    con=sqlite3.connect(f'file:{asset / "producers.sqlite"}?mode=ro',uri=True)
    primary={code:(id,name,country) for id,name,country,codes in con.execute('SELECT id,name,country_group,lwin7 FROM producers') for code in json.loads(codes)}
    con.close()
    p=json.loads((mirror/'producers.json').read_text());w=json.loads((mirror/'wines.json').read_text())
    matched=0;changed=[];not_admitted=[];seen=set()
    for row in w['w']:
        code=str(row[4]);seen.add(code);mirror_name=p['p'][row[1]][0]
        if code not in primary:not_admitted.append(code);continue
        if normalize(mirror_name)==normalize(primary[code][1]):matched+=1
        else:changed.append(dict(lwin7=code,mirror_name=mirror_name,primary_name=primary[code][1]))
    result=dict(primary_eligible_codes=len(primary),mirror_wine_rows=len(w['w']),matching_producer_name_rows=matched,
        changed_producer_names=changed,mirror_codes_not_admitted_by_strict_primary_filter=not_admitted,
        primary_codes_not_in_mirror=len(set(primary)-seen),
        note='Comparison of name fields by LWIN7, not proof of every entity identity or of source authenticity. Primary fields exclusively drive runtime names.',
        database_sha256=hashlib.sha256((asset/'producers.sqlite').read_bytes()).hexdigest())
    pathlib.Path(out).write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n');return result
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--asset',required=True);p.add_argument('--mirror',required=True);p.add_argument('--out',required=True);a=p.parse_args()
    r=compare(a.asset,a.mirror,a.out);print({k:v for k,v in r.items() if not isinstance(v,list)})
    print('changed',len(r['changed_producer_names']),'not admitted',len(r['mirror_codes_not_admitted_by_strict_primary_filter']))
