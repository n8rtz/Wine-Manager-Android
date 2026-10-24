#!/usr/bin/env python3
"""Offline audit of the pinned user-supplied code, not an approved producer data build.
Executes ONLY the reviewed Python builder/query functions in a temporary directory.
No Next.js/Postgres app, npm install, live registry download, OCR, or APK asset changes.
"""
import hashlib,json,os,sqlite3,sys,tempfile,zipfile
from pathlib import Path
HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[1]
BLOB='3749baa923a12efdb28b2bb03119a734c4faac58'
QUERIES=['Chatau Montelena','Margaux','Pavillon Rouge','Cristal','Domaine Drouhin',
         'La Cave d’Augustin Florent','Château Amélisse','Estate','PRODUCT OF FRANCE','zzqxv zzqxv']
def audit():
    data=(HERE/'source.zip').read_bytes()
    assert hashlib.sha1(b'blob '+str(len(data)).encode()+b'\0'+data).hexdigest()==BLOB
    os.environ['TTB_OFFLINE_MODE']='1'
    with tempfile.TemporaryDirectory(prefix='producer-audit-') as tmp:
        temp=Path(tmp)
        with zipfile.ZipFile(HERE/'source.zip') as archive:
            entries=[i.filename for i in archive.infolist() if not i.is_dir()]
            # Extract just the two reviewed scripts, not web-app or dependency configuration.
            for name in ('pipeline/fetch_ttb_producers.py','pipeline/query_gazetteer.py'):
                path=temp/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(archive.read(name))
        sys.path.insert(0,str(temp/'pipeline'))
        try:
            import fetch_ttb_producers as builder
            import query_gazetteer as query
            dbpath=temp/'audit.sqlite'
            summary=builder.build_gazetteer_db(str(dbpath),quiet=True)
            con=sqlite3.connect(dbpath)
            countries=dict(con.execute('SELECT country,count(*) FROM producers GROUP BY country ORDER BY country'))
            normalize=lambda text: ''.join(c for c in builder.strip_diacritics(text).casefold() if c.isalnum())
            forms={normalize(s) for name,aliases in con.execute('SELECT canonical_name,aliases FROM producers') for s in [name]+aliases.split('|')}
            con.close()
            probes=[]
            for text in QUERIES:
                result=query.cmd_search(str(dbpath),text,limit=3)
                probes.append(dict(query=text,top_results=[dict(name=r['canonical_name'],id=r['producer_id'],rank=r['rank']) for r in result['results']]))
            reference=json.loads((ROOT/'tools/evaluation/public-corpus-reference-v1/reference.json').read_text())['images']
            known=[r for r in reference if r['fields']['producer']['status']=='known']
            covered=[r for r in known if any(normalize(a) in forms for a in r['fields']['producer']['accepted'])]
            return dict(source_blob_sha1=BLOB,zip_sha256=hashlib.sha256(data).hexdigest(),zip_bytes=len(data),archive_files=entries,
                raw_registry_present='pipeline/data/ttb_cola_registry_raw.tsv.gz' in entries,
                database_present=any(n.endswith(('.sqlite','.db')) for n in entries),
                license_file_present=any(Path(n).name.lower().startswith(('license','copying')) for n in entries),
                live_downloads_enabled=False,curated_rows=len(builder.CURATED_FLAGSHIP_PRODUCERS),
                actual_producers=summary['total_producers'],actual_fts_rows=summary['total_fts_indexed'],database_bytes=dbpath.stat().st_size,
                countries=countries,search_probes=probes,
                lexical_reference_coverage=dict(matched_photos=len(covered),known_producer_photos=len(known),
                    distinct_reference_names=sorted({r['fields']['producer']['value'] for r in covered}),
                    review_numbers=[r['review_number'] for r in covered],
                    note='Exact normalized accepted-alias presence, NOT OCR accuracy, validated identity coverage or held-out evaluation.'),
                disposition='REVIEW ONLY: missing bulk data, unverified curated identities/aliases, seeded UI metrics. Not bundled into Android or enabled in Lab.')
        finally:
            sys.path.pop(0)
            sys.modules.pop('fetch_ttb_producers',None);sys.modules.pop('query_gazetteer',None)
if __name__=='__main__':
    output=audit();(HERE/'audit.json').write_text(json.dumps(output,indent=2,ensure_ascii=False)+'\n',encoding='utf-8')
    print(json.dumps({k:output[k] for k in ('actual_producers','actual_fts_rows','database_bytes','raw_registry_present','disposition')},indent=2))
