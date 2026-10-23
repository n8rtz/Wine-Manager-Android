#!/usr/bin/env python3
"""Build a deterministic, offline-only debug gazetteer from the user-supplied export."""
import argparse,collections,hashlib,json,re,sqlite3,unicodedata
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
DEFAULT_SOURCE=ROOT/'data/gazetteer/curated.json'
DEFAULT_DB=ROOT/'app/src/debug/assets/gazetteer-v1/wine_gazetteer.sqlite'
def normalize(s):
    s=''.join(c for c in unicodedata.normalize('NFD',s.lower()) if unicodedata.category(c)!='Mn')
    s=' '.join(''.join(c if c.isalnum() else ' ' for c in s).split())
    return ' '.join({'st':'saint','ste':'sainte','mt':'mount'}.get(w,w) for w in s.split())
def aliases(row):
    original=[row['appellation_name']]+[x.strip() for x in (row.get('alternate_names') or '').split(',') if x.strip()]
    values=set(original)
    for x in original:
        plain=''.join(c for c in unicodedata.normalize('NFD',x) if unicodedata.category(c)!='Mn')
        values.update([plain,plain.replace('-',' '),normalize(x),normalize(x).replace(' ','-')])
    return sorted(values)
def digest(path):return hashlib.sha256(Path(path).read_bytes()).hexdigest()
def build(source=DEFAULT_SOURCE,dest=DEFAULT_DB):
    payload=json.loads(Path(source).read_text());rows=payload['rows'];by={r['id']:r for r in rows}
    assert len(by)==len(rows)>0,'Duplicate IDs or empty input'
    paths={}
    for r in rows:
        chain=[];seen=set();node=r
        while node:
            assert node['id'] not in seen,'Hierarchy cycle'
            assert node['country']==r['country'],'Cross-country parent'
            chain.append(node['appellation_name']);seen.add(node['id']);p=node['parent_id']
            assert p is None or p in by,'Missing parent'
            node=by.get(p)
        paths[r['id']]=list(reversed(chain))
    dest=Path(dest);dest.parent.mkdir(parents=True,exist_ok=True)
    temporary=dest.with_suffix('.building');temporary.unlink(missing_ok=True)
    with sqlite3.connect(temporary) as db:
        db.executescript('''PRAGMA foreign_keys=ON;
CREATE TABLE entities(id INTEGER PRIMARY KEY,canonical_name TEXT NOT NULL,country TEXT NOT NULL,region TEXT NOT NULL,sub_region TEXT,level TEXT NOT NULL,parent_id INTEGER,official_code TEXT,source TEXT,region_path TEXT NOT NULL,aliases_json TEXT NOT NULL,path_json TEXT NOT NULL);
CREATE VIRTUAL TABLE appellations_fts USING fts5(appellation_id UNINDEXED,canonical_name,alternate_names,country UNINDEXED,region_path UNINDEXED,tokenize='trigram');
CREATE TABLE metadata(key TEXT PRIMARY KEY,value TEXT NOT NULL);
''')
        for r in sorted(rows,key=lambda r:r['id']):
            a=aliases(r);path=paths[r['id']]
            db.execute('INSERT INTO entities VALUES(?,?,?,?,?,?,?,?,?,?,?,?)',(r['id'],r['appellation_name'],r['country'],r['region'],r.get('sub_region'),r['level'],r['parent_id'],r.get('official_code'),r.get('source'),' > '.join(path),json.dumps(a,ensure_ascii=False),json.dumps(path,ensure_ascii=False)))
            db.execute('INSERT INTO appellations_fts(rowid,appellation_id,canonical_name,alternate_names,country,region_path) VALUES(?,?,?,?,?,?)',(r['id'],str(r['id']),r['appellation_name'],'\n'.join(a),r['country'],' > '.join(path)))
        db.executemany('INSERT INTO metadata VALUES(?,?)',[('schema_version','1'),('source_sha256',digest(source)),('record_count',str(len(rows))),('provenance_status','user-provided; official-source claims not independently verified')])
        assert db.execute("SELECT count(*) FROM appellations_fts WHERE appellations_fts MATCH '\"pau\"'").fetchone()[0]>0
        db.execute("INSERT INTO appellations_fts(appellations_fts) VALUES('integrity-check')")
        assert db.execute('PRAGMA integrity_check').fetchone()[0]=='ok'
        db.commit();db.execute('VACUUM')
    temporary.replace(dest)
    duplicates=collections.Counter((r['country'],normalize(r['appellation_name'])) for r in rows)
    return dict(records=len(rows),levels=dict(collections.Counter(r['level'] for r in rows)),duplicate_country_names=[dict(country=k[0],name=k[1],records=n) for k,n in sorted(duplicates.items()) if n>1],missing_official_codes=sum(not r.get('official_code') for r in rows),source_sha256=digest(source),database_sha256=digest(dest),database_bytes=dest.stat().st_size,sqlite_version=sqlite3.sqlite_version)
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--source',type=Path,default=DEFAULT_SOURCE);p.add_argument('--output',type=Path,default=DEFAULT_DB);a=p.parse_args();print(json.dumps(build(a.source,a.output),indent=2))
