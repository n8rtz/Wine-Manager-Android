"""Reference implementation of the bounded Android trigram resolver contract, not substitute OCR."""
import json,re,sqlite3
from build_sqlite import normalize,DEFAULT_DB
NOISE=re.compile(r'\b(?:sulfites?|sulphites?|sulfit[ei]s?|allergen|pregnan\w*|government|warning|importe[dr]|imported|bottled|bouteille|embouteille|distribu\w*|contains|contient|www|https?|alc|vol|alcohol)\b')
ESTATE=re.compile(r'\b(?:chateau|chatau|chateaux|domaine|bodega|winery|estate|maison)\b')
GENERIC={'merlot','chardonnay','cabernet sauvignon','pinot noir','red wine','white wine','grand cru','reserve','riserva','reserva','wine','vin'}
def distance(a,b):
    prev=list(range(len(b)+1));older=None
    for i,x in enumerate(a,1):
        cur=[i]
        for j,y in enumerate(b,1):
            value=min(cur[-1]+1,prev[j]+1,prev[j-1]+(x!=y))
            if i>1 and j>1 and x==b[j-2] and a[i-2]==y:value=min(value,older[j-2]+1)
            cur.append(value)
        older,prev=prev,cur
    return prev[-1]
def clean(raw):
    if len(raw)>160:return None
    n=normalize(raw)
    if len(n)<3 or any(c.isdigit() for c in raw) or '%' in raw or NOISE.search(n) or ESTATE.search(n) or n in GENERIC:return None
    words=n.split()
    if words and words[0]=='appellation':words.pop(0)
    while words and words[-1] in {'controlee','protegee','aoc','aop','doc','docg','dop','igp'}:words.pop()
    if words and len(words[-1])>=6 and any(distance(words[-1],x)<=1 for x in ['riserva','reserva','reserve']):words.pop()
    q=' '.join(words)
    return q if len(q)>=3 and q not in GENERIC else None

def query_for(q):
    grams=sorted({q[i:i+3] for i in range(len(q)-2) if q[i:i+3].strip()})[:96]
    return '{canonical_name alternate_names} : ('+' OR '.join('"'+g.replace('"','""')+'"' for g in grams)+')'
SQL='''SELECT e.*,bm25(appellations_fts) AS score FROM appellations_fts JOIN entities e ON e.id=appellations_fts.rowid WHERE appellations_fts MATCH ? ORDER BY score ASC,e.id ASC LIMIT 64'''
class Resolver:
    def __init__(self,path=DEFAULT_DB):
        self.db=sqlite3.connect('file:'+str(path)+'?mode=ro',uri=True);self.db.row_factory=sqlite3.Row
    def close(self):self.db.close()
    def resolve(self,raw):
        q=clean(raw)
        result=dict(raw_ocr_input=raw,matched_entity=None,confidence_score=None,confidence_kind='uncalibrated lexical similarity; not probability',bm25_rank=None,alternatives=[],reason='filtered')
        if q is None:return result
        hits=[]
        for row in self.db.execute(SQL,(query_for(q),)):
            r=dict(row);forms={normalize(x) for x in json.loads(r['aliases_json'])}
            d,form=min((distance(q,f),f) for f in forms)
            sim=1-d/max(len(q),len(form),1)
            hits.append(dict(entity=r,edit_distance=d,similarity=sim,matched_form=form))
        hits.sort(key=lambda h:(-h['similarity'],h['edit_distance'],h['entity']['score'],h['entity']['id']))
        result['alternatives']=[dict(id=h['entity']['id'],name=h['entity']['canonical_name'],similarity=h['similarity'],bm25=h['entity']['score']) for h in hits[:5]]
        if not hits:result['reason']='no candidates';return result
        h=hits[0];length=max(len(q),len(h['matched_form']));maximum=0 if length<5 else 1 if length<9 else 2
        if h['edit_distance']>maximum or h['similarity']<.82:result['reason']='weak lexical evidence';return result
        if len(hits)>1 and h['similarity']-hits[1]['similarity']<.06:result['reason']='ambiguous entities';return result
        r=h['entity'];result.update(matched_entity={k:r[k] for k in ['id','canonical_name','country','region','sub_region','level','parent_id','official_code','source','region_path']},confidence_score=h['similarity'],bm25_rank=r['score'],reason='exact alias' if h['edit_distance']==0 else 'bounded fuzzy suggestion')
        return result
