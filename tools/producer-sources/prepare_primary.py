#!/usr/bin/env python3
"""Build a Lab-only asset ONLY from an official LWIN export with recorded provenance.
No mirror fallback, DISPLAY_NAME inference, brand-from-wine inference or legal-owner aliases.
Provenance is an explicit acquisition attestation, not proof supplied by a filename.
"""
import argparse,collections,csv,hashlib,json,pathlib,sqlite3,unicodedata,urllib.parse,re

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

def build(source,provenance,out):
    provenance=json.loads(pathlib.Path(provenance).read_text())
    if provenance.get('acquisition')!='official_download_user_supplied':raise ValueError('Official acquisition attestation required; mirrors not accepted')
    url=urllib.parse.urlparse(provenance.get('source_url',''))
    if url.scheme!='https' or not(url.hostname=='liv-ex.com' or (url.hostname or '').endswith('.liv-ex.com')):raise ValueError('Official Liv-ex source URL required')
    if provenance.get('sha256')!=sha(source):raise ValueError('Primary input hash mismatch')
    if not provenance.get('retrieved_at') or not provenance.get('review_note'):raise ValueError('Acquisition date and review note required')
    groups={};seen={};wine_rows={};counts=collections.Counter()
    for row in read_rows(source):
        if not {'LWIN','STATUS','TYPE','PRODUCER_NAME','COUNTRY','WINE'}<=row.keys():raise ValueError('Missing official LWIN columns')
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
        wine=row['WINE']
        if not wine or not normalize(wine):counts['wine_name_missing']+=1;continue
        if row['LWIN'] in wine_rows and wine_rows[row['LWIN']]!=(key,wine):raise ValueError('Conflicting primary LWIN wine name')
        wine_rows[row['LWIN']]=(key,wine)
    if not groups:raise ValueError('No eligible explicit primary producer names')
    if len(groups)>100000:raise ValueError('Unexpected primary group count')
    out=pathlib.Path(out);out.mkdir(parents=True,exist_ok=True)
    # Publish manifest last; runtime verifies every payload hash. Never reuse a stale manifest during build.
    (out/'manifest.json').unlink(missing_ok=True)
    db=out/'producers.sqlite';db.unlink(missing_ok=True)
    con=sqlite3.connect(db)
    con.executescript('CREATE TABLE producers(id TEXT PRIMARY KEY,name TEXT NOT NULL,key TEXT NOT NULL,country_group TEXT NOT NULL,lwin7 TEXT NOT NULL); CREATE INDEX exact_key ON producers(key); CREATE VIRTUAL TABLE names USING fts5(id UNINDEXED,key,tokenize="trigram");')
    producer_ids={}
    for (name,country),codes in sorted(groups.items()):
        id=hashlib.sha256(json.dumps([name,country],ensure_ascii=False).encode()).hexdigest()[:24];key=normalize(name)
        producer_ids[(name,country)]=id
        con.execute('INSERT INTO producers VALUES(?,?,?,?,?)',(id,name,key,country,json.dumps(sorted(codes))))
        con.execute('INSERT INTO names VALUES(?,?)',(id,key))
    con.commit();con.execute('VACUUM');con.close()
    wine_db=out/'wines.sqlite';wine_db.unlink(missing_ok=True)
    c=sqlite3.connect(wine_db);c.execute('PRAGMA foreign_keys=ON')
    c.executescript('CREATE TABLE wine_names(id INTEGER PRIMARY KEY,name TEXT NOT NULL,key TEXT NOT NULL); CREATE INDEX wine_exact ON wine_names(key); CREATE VIRTUAL TABLE wine_fts USING fts5(key,content="wine_names",content_rowid="id",tokenize="trigram"); CREATE TABLE wine_producers(id INTEGER PRIMARY KEY,producer_id TEXT NOT NULL UNIQUE); CREATE TABLE wine_links(producer_id INTEGER NOT NULL REFERENCES wine_producers(id),wine_id INTEGER NOT NULL REFERENCES wine_names(id),lwin7 TEXT NOT NULL,PRIMARY KEY(producer_id,wine_id)) WITHOUT ROWID; CREATE INDEX wine_link_name ON wine_links(wine_id,producer_id);')
    # Store distinct explicit WINE strings once. LWIN products remain links, never producer aliases.
    wine_ids={name:i+1 for i,name in enumerate(sorted({name for _,name in wine_rows.values()}))}
    for name,id in wine_ids.items():c.execute('INSERT INTO wine_names VALUES(?,?,?)',(id,name,normalize(name)))
    local_producers={pid:i+1 for i,pid in enumerate(sorted(producer_ids.values()))}
    c.executemany("INSERT INTO wine_producers VALUES(?,?)",[(local,pid) for pid,local in local_producers.items()])
    links=collections.defaultdict(set)
    for code,(key,name) in wine_rows.items():links[(producer_ids[key],wine_ids[name])].add(code)
    for (pid,wid),codes in sorted(links.items()):c.execute('INSERT INTO wine_links VALUES(?,?,?)',(local_producers[pid],wid,json.dumps(sorted(codes))))
    c.execute("INSERT INTO wine_fts(wine_fts) VALUES('rebuild')")
    if c.execute('PRAGMA foreign_key_check').fetchall():raise ValueError('Broken wine link')
    c.commit();c.execute('VACUUM');c.close()
    manifest=dict(schema=2,primary_verified=True,verification_kind='official-export-acquisition-attested',provenance=provenance,
        version='producer-wine-primary-v3-lwin-only',wine_database_sha256=sha(wine_db),wine_names=len(wine_ids),wine_links=len(links),wine_lwin_records=len(wine_rows),database_sha256=sha(db),records=len(groups),counts=dict(counts),
        attribution='LWIN by Liv-ex, CC BY 4.0; producer/title and explicit WINE fields with LWIN7 relationships retained; non-wine/inactive/missing producer records excluded; no inferred aliases')
    (out/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
    return manifest
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--primary',required=True);p.add_argument('--provenance',required=True);p.add_argument('--out',required=True)
    a=p.parse_args();print(json.dumps(build(a.primary,a.provenance,a.out),indent=2))
