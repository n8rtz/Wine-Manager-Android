import csv,hashlib,importlib.util,json,sqlite3,tempfile,unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('prepare_primary',ROOT/'tools/producer-sources/prepare_primary.py')
p=importlib.util.module_from_spec(spec);spec.loader.exec_module(p)
class PrimaryProducerAssetChecks(unittest.TestCase):
    def prepare(self,d,rows=None):
        source=d/'primary.csv'
        header=['LWIN','STATUS','TYPE','PRODUCER_TITLE','PRODUCER_NAME','COUNTRY','DISPLAY_NAME','WINE']
        with source.open('w',newline='') as f:
            w=csv.writer(f);w.writerow(header);w.writerows([r if len(r)==8 else r+["Cuvée Test"] for r in (rows or [
                ['1234567','live','Wine','Château','Example','France','Unrelated Wine Name'],
                ['1234568','live','Spirits','','Distiller','France',''],
                ['1234569','live','Wine','','','France','Invented Fallback Winery'],
                ['1234570','retired','Wine','','Old Estate','France',''],
                ['1234571','live','','','Missing Type','France','']])])
        provenance=d/'provenance.json';provenance.write_text(json.dumps(dict(acquisition='official_download_user_supplied',
            source_url='https://www.liv-ex.com/lwin-database-download/',sha256=hashlib.sha256(source.read_bytes()).hexdigest(),
            retrieved_at='fixture-only',review_note='Synthetic test input, never ship')))
        return source,provenance
    def test_strict_primary_fields_and_real_fts(self):
        with tempfile.TemporaryDirectory() as tmp:
            d=Path(tmp);source,provenance=self.prepare(d);m=p.build(source,provenance,d/'out')
            self.assertEqual(m['records'],1)
            self.assertEqual(m['wine_names'],1)
            with sqlite3.connect(d/'out/wines.sqlite') as c:
                self.assertEqual(c.execute('select name from wine_names').fetchall(),[('Cuvée Test',)])
                self.assertEqual(c.execute('pragma foreign_key_check').fetchall(),[])
            with sqlite3.connect(d/'out/producers.sqlite') as c:
                self.assertEqual(c.execute('select name from producers').fetchall(),[('Château Example',)])
                self.assertEqual(c.execute("select count(*) from names where names match '\"exa\"'").fetchone()[0],1)
                self.assertNotIn('Unrelated Wine Name',str(c.execute('select * from producers').fetchall()))
    def test_hash_and_acquisition_gate(self):
        with tempfile.TemporaryDirectory() as tmp:
            d=Path(tmp);source,provenance=self.prepare(d)
            v=json.loads(provenance.read_text());v['acquisition']='mirror';provenance.write_text(json.dumps(v))
            with self.assertRaises(ValueError):p.build(source,provenance,d/'out')
            v['acquisition']='official_download_user_supplied';v['sha256']='0'*64;provenance.write_text(json.dumps(v))
            with self.assertRaises(ValueError):p.build(source,provenance,d/'out')
            self.assertFalse((d/'out/manifest.json').exists())
    def test_conflicting_lwin_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            d=Path(tmp);source,provenance=self.prepare(d,[['1234567','live','Wine','','First Estate','France',''],['1234567','live','Wine','','Other Estate','France','']])
            with self.assertRaises(ValueError):p.build(source,provenance,d/'out')
    def test_primary_asset_hashes_and_debug_only(self):
        folder=ROOT/'app/src/debug/assets/producer-gazetteer-v1'
        m=json.loads((folder/'manifest.json').read_text())
        self.assertEqual(m['provenance']['zip_git_blob'],'8e7457ee74946622c5858aa36ac609cf32e63ab6')
        self.assertEqual(p.sha(folder/'producers.sqlite'),m['database_sha256'])
        with sqlite3.connect(folder/'producers.sqlite') as c:
            self.assertEqual(c.execute('select count(*) from producers').fetchone()[0],m['records'])
            self.assertEqual(c.execute('select count(*) from names').fetchone()[0],m['records'])
            self.assertEqual(c.execute("select count(*) from producers where name like 'NA %'").fetchone()[0],0)
        self.assertEqual(p.sha(folder/'wines.sqlite'),m['wine_database_sha256'])
        with sqlite3.connect(folder/'wines.sqlite') as c:
            self.assertEqual(c.execute('select count(*) from wine_names').fetchone()[0],m['wine_names'])
            self.assertEqual(c.execute('select count(*) from wine_links').fetchone()[0],m['wine_links'])
            self.assertEqual(c.execute('pragma foreign_key_check').fetchall(),[])
        for item in m['support'].values():self.assertEqual(p.sha(folder/item['file']),item['sha256'])
        self.assertFalse((ROOT/'app/src/main/assets/producer-gazetteer-v1').exists())
    def test_excel_numeric_and_na_values(self):
        self.assertEqual(p.clean(1000001.0),'1000001')
        for v in ('NA','n/a',None,'-'):self.assertEqual(p.clean(v),'')
    def test_lab_only_wiring_preserves_raw(self):
        text=(ROOT/'app/src/debug/java/com/yourapp/wine/textlab/TextLabSpatialPreview.kt').read_text()
        self.assertIn('val readings=original',text)
        self.assertRegex(text,r'ProducerResolver\(producerIndex(?:,onFailureTelemetry=\{[^}]+\})?\)\.resolve\(before,spans,checkProducerBudget\)')
        self.assertIn('producer_gazetteer_status',text)
        self.assertNotIn('TextLabSpatialPhrases.readings(proposals)',text)
        v2=(ROOT/'app/src/debug/java/com/yourapp/wine/textlab/TextLabEvidencePreview.kt').read_text()
        self.assertNotIn('ProducerResolver',v2)
