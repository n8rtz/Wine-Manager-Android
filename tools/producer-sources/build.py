#!/usr/bin/env python3
"""Offline, source-separated producer candidate staging. Never an Android asset build.
No source code is executed. No company/wine aliases inferred. No network requests.
"""
import argparse,collections,gzip,hashlib,json,pathlib,re,zipfile
OSM_ARCHIVE_SHA256='abe1502aecc089226e6bd73d59e04f0d87554448c87f46f0b776427b3b2b9cf5'
TTB_BLOB='8bf9737463092b0a2ef233139dee8e88a0f6fae8'
LWIN_BLOBS={'producers.json':'0e7960726eb369085124cb3c8bc1896bf2cab60c','wines.json':'6fd1a612d9c0460c6f13b27575272011a582c068'}
NON_GRAPE=re.compile(r'\b(?:cider\w*|mead\w*|sake|saké|kombucha)\b',re.I)
def blob(data):return hashlib.sha1(b'blob '+str(len(data)).encode()+b'\0'+data).hexdigest()
def ttb_record(row):
    if len(row)<9:raise ValueError('Malformed permit row')
    if row[8]!='Wine Producer':return None,'non_wine_producer_role'
    if NON_GRAPE.search(' '.join(str(x or '') for x in row[1:3])):return None,'non_grape_name_requires_review'
    permit,owner,operating=str(row[0] or '').strip(),str(row[1] or '').strip(),str(row[2] or '').strip()
    if not permit or not(owner or operating):return None,'missing_identity'
    return dict(id='ttb:'+permit,source='ttb-permits',source_record_id=permit,
        name=operating or owner,legal_owner_name=owner,operating_name=operating,
        role='wine_permit_holder',industry=row[8],permit_address_state=row[5],
        identity_status='candidate_not_verified_label_producer',aliases=[]),None

def osm_record(f,country):
    p=f['properties'];t=p.get('tags',{});name=str(t.get('name') or p.get('name') or '').strip()
    if not(t.get('craft')=='winery' or t.get('industrial')=='winery'):return None,'not_explicit_winery'
    if any(t.get(k) in ('yes','winery') for k in ('disused','abandoned','demolished','disused:craft','abandoned:craft','demolished:craft')):return None,'inactive'
    if not name:return None,'unnamed'
    if NON_GRAPE.search(name):return None,'non_grape_name_requires_review'
    sid=str(f['id'])
    return dict(id='osm:'+sid,source='osm-winery',source_record_id=sid,name=name,
        role='mapped_winery',map_country=country,website=t.get('website') or t.get('contact:website'),
        source_tags={k:t[k] for k in ('craft','industrial','building') if k in t},
        identity_status='candidate_not_verified_label_producer',aliases=[]),None

def lwin_records(p,w):
    links=collections.defaultdict(set)
    for row in w['w']:
        if len(row)!=5 or not isinstance(row[1],int) or not 0<=row[1]<len(p['p']):raise ValueError('Bad wine/producer link')
        if not re.fullmatch(r'\d{7}',str(row[4])):raise ValueError('Invalid LWIN7')
        links[row[1]].add(str(row[4]))
    result=[];excluded=collections.Counter()
    for index,row in enumerate(p['p']):
        name,country,_,count=row
        if not isinstance(name,str) or not name.strip() or name.lower().strip() in ('unknown','n/a','none','various'):
            excluded['missing_producer']+=1;continue
        if not links[index]:excluded['no_wine_records']+=1;continue
        # The mirror groups by wine-country; this is NOT a claim of producer domicile.
        key=hashlib.sha256(json.dumps([name,country],ensure_ascii=False).encode()).hexdigest()[:24]
        result.append(dict(id='lwin-mirror:'+key,source='lwin-mirror',source_record_index=index,name=name,
            role='wine_producer_or_brand',wine_country_group=country,lwin7=sorted(links[index]),
            identity_status='candidate_from_derivative_not_primary_verified',aliases=[]))
    return result,excluded

def write_layer(out,name,rows):
    rows=sorted(rows,key=lambda r:r['id'])
    if len({r['id'] for r in rows})!=len(rows):raise ValueError('Duplicate source IDs')
    raw=''.join(json.dumps(r,ensure_ascii=False,sort_keys=True,separators=(',',':'))+'\n' for r in rows).encode()
    data=gzip.compress(raw,mtime=0);path=out/(name+'.jsonl.gz');path.write_bytes(data)
    return dict(file=path.name,records=len(rows),bytes=len(data),sha256=hashlib.sha256(data).hexdigest())

def build(ttb_zip,lwin_dir,osm_zip,out):
    out=pathlib.Path(out);out.mkdir(parents=True,exist_ok=True)
    ttb_data=pathlib.Path(ttb_zip).read_bytes()
    if blob(ttb_data)!=TTB_BLOB:raise ValueError('Unexpected TTB archive')
    with zipfile.ZipFile(ttb_zip) as z:
        name=next(n for n in z.namelist() if n.endswith('FRL_All_Permits.json'))
        rows=json.loads(z.read(name))['Permit Data']
    kept={};excluded=collections.Counter();industries=collections.Counter()
    for row in rows:
        r,why=ttb_record(row)
        if why:excluded[why]+=1;industries[row[8]]+=1
        else:
            if r['id'] in kept and kept[r['id']]!=r:raise ValueError('Conflicting permit identity')
            kept[r['id']]=r
    ttb=write_layer(out,'ttb-wine-only',list(kept.values()))
    ttb.update(input_records=len(rows),excluded=dict(excluded),excluded_industries=dict(industries),input_git_blob=TTB_BLOB,source_url='https://www.ttb.gov/public-information/foia/list-of-permittees',license_status='Source authenticity/freshness and reuse provenance require verification; supplied archive claims public-domain/CC0')
    lp=pathlib.Path(lwin_dir);payload={}
    for file,expected in LWIN_BLOBS.items():
        data=(lp/file).read_bytes()
        if blob(data)!=expected:raise ValueError('Unexpected LWIN mirror input')
        payload[file]=json.loads(data)
    records,excluded=lwin_records(payload['producers.json'],payload['wines.json'])
    lw=write_layer(out,'lwin-wine-producers',records)
    lw.update(input_groups=len(payload['producers.json']['p']),linked_wine_records=len(payload['wines.json']['w']),excluded=dict(excluded),input_git_blobs=LWIN_BLOBS,
        source_metadata=payload['producers.json']['source'],snapshot_date=payload['producers.json']['date'],mirror_commit='92ee7f1ea991cca862a86b86173e1c28ab5d57b6',mirror_url='https://github.com/doma17-wc/open-wine-atlas',changes='Producer/country groups and linked LWIN7 IDs retained; wine names and atlas geography discarded; no aliases inferred')
    records={};excluded=collections.Counter();input_count=0
    osm_bytes=pathlib.Path(osm_zip).read_bytes()
    if hashlib.sha256(osm_bytes).hexdigest()!=OSM_ARCHIVE_SHA256:raise ValueError('Unexpected OSM archive')
    # The archive prefix binds the reviewed mirror revision; file hashes are recorded below.
    prefix='openwinemap-98a5bf983abd23b1f6255bcb60bc73bd99b6b69e/'
    input_hashes={}
    with zipfile.ZipFile(osm_zip) as z:
        for name in sorted(z.namelist()):
            if not name.startswith(prefix+'data/countries/') or not name.endswith('.geojson'):continue
            data=z.read(name);input_hashes[name]=hashlib.sha256(data).hexdigest()
            for f in json.loads(data)['features']:
                input_count+=1;r,why=osm_record(f,pathlib.PurePosixPath(name).stem)
                if why:excluded[why]+=1
                elif r['id'] in records:excluded['duplicate_osm_id']+=1
                else:records[r['id']]=r
    if not input_count:raise ValueError('Missing pinned OSM inputs')
    osm=write_layer(out,'osm-wineries',list(records.values()))
    osm.update(input_features=input_count,excluded=dict(excluded),input_archive_sha256=hashlib.sha256(osm_bytes).hexdigest(),input_files=input_hashes,
        attribution='© OpenStreetMap contributors; derivative snapshot by OpenHistoryMap/openwinemap',license='ODbL-1.0',mirror_commit='98a5bf983abd23b1f6255bcb60bc73bd99b6b69e',mirror_url='https://github.com/openhistorymap/openwinemap',changes='Named explicit craft/industrial wineries only; non-producer venues, unnamed and non-grape-name records excluded; no alias generation')
    result=dict(schema_version=1,status='SOURCE_SEPARATED_CANDIDATES_NOT_ANDROID_ASSETS',layers={'ttb':ttb,'lwin':lw,'osm':osm},
        caveats=['No cross-source identity merging or wine-name aliases.',
                 'Filters remove known non-producer roles; they do not establish every surviving record as a verified grape-wine producer.',
                 'Source jurisdiction, map country and wine country groups must not fill label origin fields.',
                 'ODbL layer remains separate; separation alone is not a legal determination for future combined products.'])
    (out/'manifest.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    return result
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--ttb-zip',required=True);p.add_argument('--lwin-dir',required=True);p.add_argument('--osm-zip',required=True);p.add_argument('--out',required=True)
    a=p.parse_args();r=build(a.ttb_zip,a.lwin_dir,a.osm_zip,a.out)
    print(json.dumps({k:{x:v[x] for x in ('records','bytes','excluded')} for k,v in r['layers'].items()},indent=2))
