#!/usr/bin/env python3
"""Fetch pinned review inputs from GitHub. Does not execute downloaded code.
Raw mixed-source archives are audit inputs ONLY; build.py emits filtered layers.
"""
import argparse,base64,hashlib,json,pathlib,subprocess,urllib.request
from build import OSM_ARCHIVE_SHA256,TTB_BLOB,LWIN_BLOBS,blob

def github_blob(repo,sha,path):
    if path.exists() and blob(path.read_bytes())==sha:return
    response=json.loads(subprocess.check_output(['gh','api',f'repos/{repo}/git/blobs/{sha}']))
    data=base64.b64decode(response['content'])
    if blob(data)!=sha:raise ValueError('Git blob checksum mismatch')
    path.write_bytes(data)

def fetch(out):
    out=pathlib.Path(out);out.mkdir(parents=True,exist_ok=True)
    github_blob('n8rtz/Wine-Reference-Drop',TTB_BLOB,out/'ttb-rezip.zip')
    for name,sha in LWIN_BLOBS.items():github_blob('doma17-wc/open-wine-atlas',sha,out/name)
    path=out/'openwinemap.zip'
    if not path.exists() or hashlib.sha256(path.read_bytes()).hexdigest()!=OSM_ARCHIVE_SHA256:
        url='https://codeload.github.com/openhistorymap/openwinemap/zip/98a5bf983abd23b1f6255bcb60bc73bd99b6b69e'
        with urllib.request.urlopen(url,timeout=60) as response:data=response.read()
        if hashlib.sha256(data).hexdigest()!=OSM_ARCHIVE_SHA256:raise ValueError('OSM archive checksum mismatch')
        path.write_bytes(data)
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--out',required=True);a=p.parse_args();fetch(a.out)
