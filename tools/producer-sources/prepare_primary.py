#!/usr/bin/env python3
"""Build a Lab-only asset ONLY from an official LWIN export with recorded provenance.
No mirror fallback, DISPLAY_NAME inference, brand-from-wine inference or legal-owner aliases.
Provenance is an explicit acquisition attestation, not proof supplied by a filename.
"""
import argparse,collections,csv,gzip,hashlib,json,pathlib,sqlite3,unicodedata,urllib.parse,re

def normalize(s):
    import re
    return re.sub(r'\s+',' ',re.sub(r'[^\w]+|_', ' ', ''.join(c for c in unicodedata.normalize('NFKD',s.lower().replace('’',"'")) if not unicodedata.combining(c)))).strip()
def clean(v):
    if v is None:return ''
    if isinstance(v,float) and v.is_integer():v=int(v)
    s=str(v).strip()
    return '' if s.casefold() in ('na','n/a','none','null','nan','-') else s

def sha(p):return hashlib.sha256(pathlib.Path(p).read_bytes()).hexdigest()
def read_rows(p):
    p=pathlib.Path(p)
    if p.suffix.lower()=='.csv':
        with p.open(encoding='utf-8-sig',newline='') as f:
            yield from csv.DictReader(f)
    elif p.suffix.lower()=='.xlsx':
        import openpyxl
        wb=openpyxl.load_workbook(p,read_only=True,data_only=True)
        try:
            it=iter(wb.worksheets[0].iter_rows(values_only=True));header=[str(x or '').strip() for x in next(it)]
            if 'LWIN' not in header:raise ValueError('Expected official header row containing LWIN')
            for row in it:yield dict(zip(header,row))
        finally:wb.close()
    else:raise ValueError('Expected official CSV or XLSX')

def build(source,provenance,out,support_dir=None):
    provenance=json.loads(pathlib.Path(provenance).read_text())
    if provenance.get('acquisition')!='official_download_user_supplied':raise ValueError('Official acquisition attestation required; mirrors not accepted')
    url=urllib.parse.urlparse(provenance.get('source_url',''))
    if url.scheme!='https' or not(url.hostname=='liv-ex.com' or (url.hostname or '').endswith('.liv-ex.com')):raise ValueError('Official Liv-ex source URL required')
    if provenance.get('sha256')!=sha(source):raise ValueError('Primary input hash mismatch')
    if not provenance.get('retrieved_at') or not provenance.get('review_note'):raise ValueError('Acquisition date and review note required')
    groups={};seen={};counts=collections.Counter()
    for row in read_rows(source):
        if not {'LWIN','STATUS','TYPE','PRODUCER_NAME','COUNTRY'}<=row.keys():raise ValueError('Missing official LWIN columns')
        row={k:clean(v) for k,v in row.items()}
        counts['input']+=1
        if row['STATUS'].casefold() not in ('live','active'):counts['non_live']+=1;continue
        if row['TYPE'].casefold() not in ('wine','fortified wine','sparkling wine'):counts['not_explicit_wine']+=1;continue
        if re.search(r'\b(?:assortment|mixed case|presentation box|gift (?:box|set)|tasting set|collection box)\b',row.get('DISPLAY_NAME',''),re.I) or row.get('COLOUR','').casefold()=='mixed':counts['mixed_or_gift_pack']+=1;continue
        name=' '.join(x for x in (row.get('PRODUCER_TITLE'),row['PRODUCER_NAME']) if x)
        if not row['PRODUCER_NAME'] or not row['COUNTRY']:counts['missing_identity']+=1;continue
        if not row['LWIN'].isdigit() or len(row['LWIN'])!=7:raise ValueError('Invalid primary LWIN7')
        key=(name,row['COUNTRY'])
        if row['LWIN'] in seen and seen[row['LWIN']]!=key:raise ValueError('Conflicting primary LWIN producer')
        seen[row['LWIN']]=key;groups.setdefault(key,set()).add(row['LWIN'])
    if not groups:raise ValueError('No eligible explicit primary producer names')
    if len(groups)>100000:raise ValueError('Unexpected primary group count')
    out=pathlib.Path(out);out.mkdir(parents=True,exist_ok=True)
    # Publish manifest last; runtime verifies every payload hash. Never reuse a stale manifest during build.
    (out/'manifest.json').unlink(missing_ok=True)
    db=out/'producers.sqlite';db.unlink(missing_ok=True)
    con=sqlite3.connect(db)
    con.executescript('CREATE TABLE producers(id TEXT PRIMARY KEY,name TEXT NOT NULL,key TEXT NOT NULL,country_group TEXT NOT NULL,lwin7 TEXT NOT NULL); CREATE INDEX exact_key ON producers(key); CREATE VIRTUAL TABLE names USING fts5(id UNINDEXED,key,tokenize="trigram");')
    for (name,country),codes in sorted(groups.items()):
        id=hashlib.sha256(json.dumps([name,country],ensure_ascii=False).encode()).hexdigest()[:24];key=normalize(name)
        con.execute('INSERT INTO producers VALUES(?,?,?,?,?)',(id,name,key,country,json.dumps(sorted(codes))))
        con.execute('INSERT INTO names VALUES(?,?)',(id,key))
    con.commit();con.execute('VACUUM');con.close()
    support={}
    if support_dir:
        for src,file,name_field in [('ttb','ttb-wine-only.jsonl.gz','operating_name'),('osm','osm-wineries.jsonl.gz','name')]:
            path=pathlib.Path(support_dir)/file
            if not path.exists():continue
            target=out/(src+'-support.sqlite');target.unlink(missing_ok=True)
            c=sqlite3.connect(target);c.execute('CREATE TABLE support(key TEXT,id TEXT,PRIMARY KEY(key,id))')
            with gzip.open(path,'rt',encoding='utf-8') as f:
                for line in f:
                    r=json.loads(line)
                    if src=='ttb' and (r.get('industry')!='Wine Producer' or r.get('role')!='wine_permit_holder'):continue
                    if src=='osm' and (r.get('role')!='mapped_winery' or not any(r.get('source_tags',{}).get(k)=='winery' for k in ('craft','industrial'))):continue
                    value=r.get(name_field,'')
                    if value:c.execute('INSERT OR IGNORE INTO support VALUES(?,?)',(normalize(value),r['id']))
            c.commit();c.execute('VACUUM');c.close()
            support[src]=dict(file=target.name,sha256=sha(target),input_sha256=sha(path),license='ODbL-1.0' if src=='osm' else 'TTB provenance pending review')
    manifest=dict(schema=1,primary_verified=True,verification_kind='official-export-acquisition-attested',provenance=provenance,
        version='producer-primary-v1',database_sha256=sha(db),records=len(groups),counts=dict(counts),support=support,
        attribution='LWIN by Liv-ex, CC BY 4.0; producer/title fields and LWIN7 links retained; non-wine/inactive/missing producer records excluded; no inferred aliases',
        support_attribution='OSM: © OpenStreetMap contributors, ODbL 1.0, snapshot via OpenHistoryMap; TTB: supplied FOIA permit snapshot. Separate files; support never supplies producer names or extra OCR votes.')
    (out/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
    return manifest
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--primary',required=True);p.add_argument('--provenance',required=True);p.add_argument('--out',required=True);p.add_argument('--support-dir')
    a=p.parse_args();print(json.dumps(build(a.primary,a.provenance,a.out,a.support_dir),indent=2))
