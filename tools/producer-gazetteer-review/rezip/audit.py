#!/usr/bin/env python3
"""Review the pinned second archive offline. Not an Android asset builder.
Run with --archive <downloaded rezip.zip>. Bulk inputs remain outside Git.
"""
import argparse,collections,hashlib,importlib.util,json,pathlib,sqlite3,sys,tempfile,zipfile
ROOT=pathlib.Path(__file__).resolve().parents[3]
BLOB='8bf9737463092b0a2ef233139dee8e88a0f6fae8'
def audit(archive, output):
    data=pathlib.Path(archive).read_bytes()
    assert hashlib.sha1(b'blob '+str(len(data)).encode()+b'\0'+data).hexdigest()==BLOB,'Unexpected input archive'
    with tempfile.TemporaryDirectory(prefix='producer-rezip-') as temp:
        temp=pathlib.Path(temp)
        with zipfile.ZipFile(archive) as z:
            assert sum(i.file_size for i in z.infolist())<40_000_000
            files={i.filename:dict(bytes=i.file_size,sha256=hashlib.sha256(z.read(i)).hexdigest()) for i in z.infolist() if not i.is_dir()}
            for name in files:
                path=pathlib.PurePosixPath(name)
                assert not path.is_absolute() and '..' not in path.parts
                # Only inspected builder and its cached input files; do not run/install the web app.
                if '/pipeline/' in name:
                    p=temp/path;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(z.read(name))
        script=next(temp.glob('*/pipeline/fetch_ttb_producers.py'))
        spec=importlib.util.spec_from_file_location('reviewed_producer_builder',script)
        m=importlib.util.module_from_spec(spec);sys.modules[spec.name]=m;spec.loader.exec_module(m)
        def deny(*args,**kwargs): raise AssertionError('Network access forbidden during audit')
        m.http_get=deny
        raw=m.load_raw_records(offline=True,limit=None)
        keys=[tuple(getattr(r,k) for k in m.FIELDS) for r in raw]
        industries=collections.Counter(r.industry for r in raw)
        unique_industries=collections.Counter(k[-1] for k in set(keys))
        all_entities=m.build_producers(raw)
        wine_entities=m.build_producers([r for r in raw if r.industry=='Wine Producer'])
        db=temp/'audit.sqlite';m.write_database(str(db),all_entities)
        con=sqlite3.connect(db)
        counts=dict(producers=con.execute('select count(*) from producers').fetchone()[0],fts=con.execute('select count(*) from producers_fts').fetchone()[0])
        con.close()
        probes={}
        for term in ['Domaine Drouhin','Chateau Montelena','Blackstone','Ridge Vineyards','Chateau Margaux','Costco','Walmart']:
            hits=[p for p in all_entities if any(norm(term)==norm(a) for a in [p.canonical_name]+p.aliases)]
            probes[term]=[dict(name=p.canonical_name,industries=sorted(p.industries),permits=sorted(p.permits),aliases=p.aliases) for p in hits]
        cache=next(temp.glob('*/pipeline/.cache'))
        json_payload=json.loads(next(cache.glob('*All_Permits.json')).read_text())
        result=dict(source_url='https://github.com/n8rtz/Wine-Reference-Drop/blob/main/build-wine-producer-gazetteer%20(rezip).zip',source_blob_sha1=BLOB,
            archive_sha256=hashlib.sha256(data).hexdigest(),archive_bytes=len(data),expanded_bytes=sum(v['bytes'] for v in files.values()),files=files,
            hidden_cache_bytes=sum(p.stat().st_size for p in cache.iterdir()),source_report_header=json_payload.get('Report Header'),
            json_rows=len(json_payload['Permit Data']),raw_rows=len(raw),unique_parsed_records=len(set(keys)),unique_permits=len({r.permit for r in raw}),
            industry_rows=dict(industries),unique_record_industries=dict(unique_industries),all_entity_counts=counts,all_database_bytes=db.stat().st_size,
            entities_with_wine_role=sum('Wine Producer' in p.industries for p in all_entities),wine_only_entities=len(wine_entities),
            reference_evaluation=dict(status='not_performed',reason='The former 168-image reference is retired and is not used for current source evaluation.'),
            exact_alias_probes=probes,
            limitations=['Cached source authenticity/freshness and CC0 claim not independently verified against TTB.',
             'Supplied writer drops permits and industries from SQLite; owner/operating-name aliases conflate roles.',
             'Offline source review only; no Android integration, OCR rerun or measured accuracy improvement.'])
        pathlib.Path(output).write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
        print(json.dumps({k:result[k] for k in ['raw_rows','unique_parsed_records','unique_permits','unique_record_industries','all_entity_counts','all_database_bytes','wine_only_entities']},indent=2))
        print('LEXICAL',result['lexical_all']['photos'],result['lexical_wine_only']['photos'])
        sys.modules.pop(spec.name,None)
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--archive',required=True);p.add_argument('--output',default=str(pathlib.Path(__file__).with_name('audit.json')))
    args=p.parse_args();audit(args.archive,args.output)
