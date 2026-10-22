"""Reference-free saved-output selection experiments, NOT a replacement OCR/parser.

No references, labels, photo IDs, recognition KB or score tables enter this module.
Policies operate only on two saved predictions and their original OCR observations.
"""
import itertools
import re
import unicodedata

FIELDS=('producer','wine_name','country','region','subregion','vintage')
GEO=('country','region','subregion')

def norm(text):
    return ''.join(c for c in unicodedata.normalize('NFKD',text.casefold().replace('&',' et ')) if c.isalnum() and not unicodedata.combining(c))

def configs():
    result=[]
    # All 16 group-level source combinations; no reference-derived favorite fields.
    for bits in itertools.product((0,1),repeat=4):
        result.append(dict(id='group-'+''.join(map(str,bits)),kind='groups',bits=bits))
    # Explicit stress test of independent field mixing, including geography hazards.
    for bits in itertools.product((0,1),repeat=6):
        result.append(dict(id='field-'+''.join(map(str,bits)),kind='fields',bits=bits))
    for prefer,fallback,disagree in itertools.product((0,1),(False,True),('prefer','abstain')):
        result.append(dict(id=f'hybrid-{prefer}-{int(fallback)}-{disagree}',kind='hybrid',prefer=prefer,fallback=fallback,disagree=disagree))
    for prefer,gap,minimum in itertools.product((0,1),(0,2,4),(0,2,4)):
        result.append(dict(id=f'evidence-{prefer}-{gap}-{minimum}',kind='evidence',prefer=prefer,gap=gap,minimum=minimum))
    result.append(dict(id='agreement-only',kind='agreement'))
    return result

def evidence(value,field,readings):
    """Inspectable ordinal score, not a calibrated confidence/probability."""
    if not value:return -100
    key=norm(value)
    matches=[r for r in readings if key and key in norm(r['raw_text'])]
    engines={r['candidate'] for r in matches}
    points=2*len(engines)
    if field=='producer':
        words=re.findall(r'[a-z]+',unicodedata.normalize('NFKD',value.lower()))
        generic={'chateau','domaine','maison','estate','winery','de','du','des','la','le','les','l','d','family'}
        if words and all(w in generic for w in words):points-=8
        if len(words)>=2 and words[0] in ('chateau','domaine','maison'):points+=1
        if matches and all(re.search(r'mis.?en.?bouteille|bottled.?by',r['raw_text'],re.I) for r in matches):points-=3
    if re.search(r'mis.?en.?bouteille|contains.?sulf|produit.?de|\d+\s*%',value,re.I):points-=8
    if field=='vintage':
        if not re.fullmatch(r'(19|20)\d{2}',value):points-=8
        if matches and all(re.search(r'fond[eé]|since|depuis|established|medail|medal',r['raw_text'],re.I) for r in matches):points-=6
    return points

def select(config,a,b,readings):
    """Return values and source choices. -1 is abstention; no invented values."""
    sources=(a,b);out={};owners={}
    def put(field,which):
        out[field]='' if which<0 else sources[which][field]
        owners[field]=which
    kind=config['kind']
    if kind in ('fields','groups'):
        bits=config['bits']
        for i,f in enumerate(FIELDS):
            j=i if kind=='fields' else (0 if f=='producer' else 1 if f=='wine_name' else 3 if f=='vintage' else 2)
            put(f,bits[j])
    else:
        # Geography always travels as an intact bundle in the runtime-style policies.
        for fields in [('producer',),('wine_name',),GEO,('vintage',)]:
            av=tuple(norm(a[f]) for f in fields);bv=tuple(norm(b[f]) for f in fields)
            same=av==bv
            if kind=='agreement':chosen=0 if same else -1
            elif kind=='hybrid':
                chosen=config['prefer'];other=1-chosen
                if config['fallback'] and not any(sources[chosen][f] for f in fields):chosen=other
                elif not same and any(av) and any(bv) and config['disagree']=='abstain':chosen=-1
            else:
                quality=[sum(evidence(src[f],f,readings) for f in fields if src[f]) if any(src[f] for f in fields) else -100 for src in sources]
                chosen=config['prefer'] if quality[0]==quality[1] else int(quality[1]>quality[0])
                if quality[chosen]<config['minimum'] or (not same and quality[chosen]-quality[1-chosen]<config['gap']):chosen=-1
            for f in fields:put(f,chosen)
    return out,owners
