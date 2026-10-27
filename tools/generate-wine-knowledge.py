#!/usr/bin/env python3
"""Offline deterministic catalog compilation. --check detects stale CSV/Kotlin outputs; no network."""
import argparse,csv,io,json,re,unicodedata
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]; DATA=ROOT/'data/wine-knowledge'
def norm(s): return ''.join(c for c in unicodedata.normalize('NFKD',s.lower()) if not unicodedata.combining(c)).strip()
def slug(s): return re.sub('[^a-z0-9]+','-',norm(s)).strip('-')
STATES=dict(x.split(':') for x in ('AL:Alabama|AK:Alaska|AZ:Arizona|AR:Arkansas|CA:California|CO:Colorado|CT:Connecticut|DE:Delaware|FL:Florida|GA:Georgia|HI:Hawaii|ID:Idaho|IL:Illinois|IN:Indiana|IA:Iowa|KS:Kansas|KY:Kentucky|LA:Louisiana|ME:Maine|MD:Maryland|MA:Massachusetts|MI:Michigan|MN:Minnesota|MS:Mississippi|MO:Missouri|MT:Montana|NE:Nebraska|NV:Nevada|NH:New Hampshire|NJ:New Jersey|NM:New Mexico|NY:New York|NC:North Carolina|ND:North Dakota|OH:Ohio|OK:Oklahoma|OR:Oregon|PA:Pennsylvania|RI:Rhode Island|SC:South Carolina|SD:South Dakota|TN:Tennessee|TX:Texas|UT:Utah|VT:Vermont|VA:Virginia|WA:Washington|WV:West Virginia|WI:Wisconsin|WY:Wyoming').split('|'))
ambiguous={'Orange','Denmark','Bourg','Graves','Cassis','Palette','Asti','Gavi','Faro','Pico','Samos','Eger','Kent','Surrey','Dorset','Cornwall','Douglas','Colonia','Rivera','Augusta','Hermann','Warren Hills','Hudson River Region'}
rows={};country_names={}

def parent_path(path):
    """Header path 'Bordeaux/Médoc/...' -> the parents a row nests under (broad region first)."""
    parts=[p.strip() for p in path.split('/') if p.strip()]
    # A blank region still means "not established here" (country-only knowledge), not an error.
    if len(parts)>3: raise ValueError(f'Geography path too deep: {path}')
    return parts

def add(code,country,regions,name,aliases,role,source,reference,path=''):
    key=(code,norm(name));country_names[code]=country
    parents=parent_path(path) if path else []
    # A name may be its own containing region/place ('Bordeaux' inside Bordeaux): the hierarchy
    # builder reuses that place instead of creating a second one.
    # A deeper path always wins over a shorter one that is its prefix: 'Bordeaux' -> 'Bordeaux/Médoc'.
    # Unrelated regions for the same name stay a hard error instead of silently picking one.
    # The broad region alone is already in `regions`; only deeper chains are stored separately.
    stored='/'.join(parents) if len(parents)>1 else ''
    if key in rows:
        old=rows[key]
        old_path=old['path']
        if old['regions']!=regions:
            # Two established, different regions for one name stay a hard error. A blank region is
            # "not established here", so a later authored row may refine it (Canberra District).
            if old['regions'] and regions: raise ValueError(f'Conflicting authored region: {name}: {old["regions"]} / {regions}')
            if not regions:
                old['aliases']=';'.join(sorted(set(old['aliases'].split(';')+aliases)-{''}))
                return
            old['regions']=regions
        if old_path and stored and not (stored.startswith(old_path+'/') or old_path.startswith(stored+'/')):
            raise ValueError(f'Conflicting authored path: {name}: {old_path} / {stored}')
        if len(stored)>len(old_path):
            old['path']=stored;old['regions']=regions
        old['aliases']=';'.join(sorted(set(old['aliases'].split(';')+aliases)-{''}))
        # A geography role is the weaker claim (no protected-status assertion) and wins on merge.
        if role=='geography': old['name_role']=role
        return
    row_id=code.lower()+'-'+slug(name)
    # Spacing/punctuation variants of one name ('Pic Saint-Loup' / 'Pic-Saint-Loup') must not
    # become two IDs: the first spelling stays canonical and the variant becomes an alias.
    twin=next((r for r in rows.values() if r['id']==row_id and r['country_code']==code),None)
    if twin is not None:
        twin['aliases']=';'.join(sorted(set(twin['aliases'].split(';')+aliases+[name])-{''}))
        if len(stored)>len(twin['path']): twin['path']=stored;twin['regions']=regions
        return
    rows[key]=dict(id=row_id,name=name,country_code=code,country=country,regions=regions,
        aliases=';'.join(aliases),name_role=role,match_policy='context_required' if name in ambiguous or len(slug(name))<5 else 'exact',
        review='reference_confirm',path=stored,source_id=source,source_record=reference)
def read_authored(files):
    for path in files:
        header=None
        for line in path.read_text().splitlines():
            line=line.strip()
            if not line or line.startswith('#'):continue
            if '|' in line:
                header=[part.strip() for part in line.split('|')]
                # 4 fields: ISO | country | region path ('Region' or 'Region/Subregion') | name role.
                assert len(header)==4,(path,line)
                parent_path(header[2])
                continue
            assert header is not None,(path,line)
            name,_,aliases=line.partition('~')
            # 'Name ~ alias' lines: strip the separator's surrounding spaces off the canonical name.
            name=name.strip()
            add(*header[:2],header[2].split('/')[0],name,[s.strip() for s in aliases.split(';') if s.strip()],header[3],
                'editorial-v1','Project-authored; not a legal registry',header[2])

# Authored inputs first (editorial plus the per-country geography files), then the pinned U.S. snapshot.
read_authored([DATA/'editorial-origins.txt']+sorted((DATA/'geography').glob('*.txt')))

# A path element that has no row of its own (a zone, region or subregion that only appears inside a
# deeper header) still has to exist as a place, or the hierarchy would invent an unnamed parent.
implied={}
for row in list(rows.values()):
    if not row['path']: continue
    parts=row['path'].split('/')
    for index in range(len(parts)):
        name=parts[index]
        key=(row['country_code'],norm(name))
        if key in rows or key in implied: continue
        implied[key]=dict(country_code=row['country_code'],country=row['country'],name=name,
            regions=parts[0] if index else '',path='/'.join(parts[:index]) if index>1 else '')
for (code,_),item in sorted(implied.items()):
    add(code,item['country'],item['regions'],item['name'],[],'geography','editorial-v1',
        'Implied containing place of an authored geography path',item['path'])
snapshot=json.loads((DATA/'sources/ucd-ava-properties.json').read_text())
for item in snapshot['records']:
    for p in item['properties']:
        states=sorted(set(STATES.get(s.strip(),s.strip()) for s in p['state'].split('|')))
        assert all(s in STATES.values() for s in states),(p['name'],states)
        alias=(p.get('aka') or '').strip()
        aliases=[a.strip() for a in alias.split('|') if a.strip() and a.strip().lower() not in ('n/a','none','null')]
        add('US','United States',';'.join(states),p['name'].strip(),aliases,'geography','ucd-ava',item['file'])
rows=sorted(rows.values(),key=lambda r:r['id'])
assert len({r['id'] for r in rows})==len(rows)
for r in rows:
    assert r['country_code'] in country_names and r['name_role'] in ('appellation','geography')
    assert all('|' not in v and '\n' not in v for v in r.values())
# Avoid publishing unreviewed aliases that collide with a different canonical place.
canonical={(r['country_code'],norm(r['name'])):r['id'] for r in rows}
for r in rows:
    r['aliases']=';'.join(a for a in r['aliases'].split(';') if a and canonical.get((r['country_code'],norm(a)),r['id'])==r['id'])
fields=list(rows[0]);out=io.StringIO(newline='');writer=csv.DictWriter(out,fieldnames=fields,lineterminator='\n');writer.writeheader();writer.writerows(rows)
terms=list(csv.DictReader((DATA/'label-terms.csv').open()))
assert len({(t['role'],norm(t['term'])) for t in terms})==len(terms)
assert all(t['action'] in {'append_to_name','producer_role_only','reject_identity','context_only','never_infer_origin_or_grape','never_infer_chardonnay','never_infer_pinot_noir','never_infer_grape','never_infer_australian_region','never_infer_sweetness'} for t in terms)
grapes=list(csv.DictReader((DATA/'typical-grapes.csv').open()));assert all(g['action']=='never_autofill' for g in grapes)
def quoted(s):return json.dumps(s,ensure_ascii=False).replace('$','\\$')
code='''// GENERATED by tools/generate-wine-knowledge.py. Edit data/wine-knowledge inputs, not this file.
package com.yourapp.wine.kb

internal object GeneratedWineKnowledge {
    val origins = listOf(\n'''
for r in rows:
    code+='        '+quoted('|'.join(r[k] for k in ['name','country_code','country','regions','aliases','name_role','match_policy','source_id','path']))+',\n'
code+='    )\n    val labelTerms = listOf(\n'
for t in terms:code+='        '+quoted('|'.join(t.values()))+',\n'
code+='    )\n}\n'
outputs={DATA/'origins.csv':out.getvalue(),ROOT/'app/src/main/java/com/yourapp/wine/kb/GeneratedWineKnowledge.kt':code}
check=argparse.ArgumentParser();check.add_argument('--check',action='store_true');args=check.parse_args()
for path,value in outputs.items():
    if args.check:
        assert path.exists() and path.read_text()==value,f'Stale generated file: {path}'
    else:path.parent.mkdir(parents=True,exist_ok=True);path.write_text(value)
nested=sum(1 for r in rows if r['path'])
print(f'{len(rows)} origin rows ({nested} nested under a subregion); {len(country_names)} countries; {len(terms)} label terms; {len(grapes)} non-inferential grape associations')
