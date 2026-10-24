import importlib.util,json,tempfile,unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('producer_source_build',ROOT/'tools/producer-sources/build.py')
b=importlib.util.module_from_spec(spec);spec.loader.exec_module(b)
class ProducerSourceFilters(unittest.TestCase):
    def row(self,role='Wine Producer',owner='Example LLC',operating='Example Estate'):
        return ['TEST-W-1',owner,operating,'not stored','City','CA','','',role,0]
    def test_non_producer_roles_excluded(self):
        for role in ['Importer (Alcohol)','Wholesaler (Alcohol)','Distilled Spirits Plant','','wine producer']:
            self.assertIsNone(b.ttb_record(self.row(role))[0])
    def test_owner_not_an_alias_and_no_origin_fill(self):
        r,why=b.ttb_record(self.row());self.assertIsNone(why)
        self.assertEqual(r['legal_owner_name'],'Example LLC');self.assertEqual(r['operating_name'],'Example Estate')
        self.assertEqual(r['aliases'],[]);self.assertNotIn('country',r)
    def test_non_grape_names_excluded_for_review(self):
        for name in ['Sample Cider Co','Sample Meadery','Sake House']:
            self.assertEqual(b.ttb_record(self.row(operating=name))[1],'non_grape_name_requires_review')
    def feature(self,tags):return {'id':'node/1','properties':{'tags':tags}}
    def test_map_roles_strict(self):
        for tags in [{'shop':'wine','name':'Shop'},{'landuse':'vineyard','name':'Vines'},{'building':'winery','name':'Building'},{'craft':'winery','name':''},{'craft':'winery','name':'Old','abandoned':'yes'}]:
            self.assertIsNone(b.osm_record(self.feature(tags),'FR')[0])
        self.assertIsNotNone(b.osm_record(self.feature({'craft':'winery','name':'Estate'}),'FR')[0])
    def test_lwin_wine_names_not_producer_aliases(self):
        p={'p':[['Example','FR',[],1]]};w={'w':[['Wine name',0,-1,'r','1234567']]}
        rows,_=b.lwin_records(p,w);self.assertEqual(rows[0]['aliases'],[])
        self.assertEqual(rows[0]['name'],'Example');self.assertEqual(rows[0]['lwin7'],['1234567'])
        with self.assertRaises(ValueError):b.lwin_records(p,{'w':[['Wine',3,-1,'r','1234567']]})
    def test_deterministic_export(self):
        with tempfile.TemporaryDirectory() as t:
            rows=[{'id':'b','name':'B'},{'id':'a','name':'A'}]
            a=b.write_layer(Path(t),'sample',rows);c=b.write_layer(Path(t),'sample',list(reversed(rows)))
            self.assertEqual(a,c)
            with self.assertRaises(ValueError):b.write_layer(Path(t),'bad',[rows[0],rows[0]])
    def test_saved_accounting(self):
        a=json.loads((ROOT/'tools/producer-sources/audit.json').read_text())['layers']
        self.assertEqual(a['ttb']['records']+sum(a['ttb']['excluded'].values()),84689)
        self.assertEqual(a['osm']['records']+sum(a['osm']['excluded'].values()),a['osm']['input_features'])
        self.assertEqual(a['lwin']['records']+sum(a['lwin']['excluded'].values()),a['lwin']['input_groups'])
