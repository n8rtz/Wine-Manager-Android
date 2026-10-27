import hashlib,importlib.util,json,sqlite3,tempfile,unittest
from pathlib import Path
import test_primary_producer_asset as primary_tests
ROOT=Path(__file__).resolve().parents[2]
class LinkedWineAssetChecks(unittest.TestCase):
    def test_explicit_wine_and_no_display_fallback(self):
        spec=importlib.util.spec_from_file_location('linked_primary',ROOT/'tools/producer-sources/prepare_primary.py')
        m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
        with tempfile.TemporaryDirectory() as tmp:
            d=Path(tmp);source,provenance=primary_tests.PrimaryProducerAssetChecks().prepare(d,[
                ['1234567','live','Wine','','Estate One','France','Estate One, Invented From Display',''],
                ['1234568','live','Wine','','Estate Two','France','Full Marketing Name','Crystal Cuvee'],
                ['1234569','live','Wine','','Estate Three','France','Different Marketing Name','Crystal Cuvee'],
                ['1234570','live','Wine','','Estate Two','France','Another product','Crystal Cuvee']])
            result=m.build(source,provenance,d/'out')
            self.assertEqual(result['wine_names'],1)
            self.assertEqual(result['wine_links'],2)
            self.assertEqual(result['wine_lwin_records'],3)
            with sqlite3.connect(d/'out/wines.sqlite') as c:
                self.assertEqual(c.execute('SELECT name FROM wine_names').fetchall(),[('Crystal Cuvee',)])
                self.assertEqual(c.execute('SELECT count(*) FROM wine_fts WHERE wine_fts MATCH ?',('"crys"',)).fetchone()[0],1)
                self.assertEqual(c.execute('PRAGMA foreign_key_check').fetchall(),[])
    def test_packaged_links_and_actual_lookup_sql(self):
        folder=ROOT/'app/src/main/assets/producer-gazetteer-v1'
        primary=ROOT/'app/src/main/assets/producer-gazetteer-v1'
        manifest=json.loads((primary/'manifest.json').read_text())
        self.assertEqual(manifest['schema'],2)
        for file in folder.glob('*.sqlite'):self.assertLessEqual(file.stat().st_size,32*1024*1024)
        self.assertEqual(hashlib.sha256((folder/'wines.sqlite').read_bytes()).hexdigest(),manifest['wine_database_sha256'])
        with sqlite3.connect(folder/'wines.sqlite') as c:
            c.execute('ATTACH DATABASE ? AS primary_db',(str(primary/'producers.sqlite'),))
            self.assertEqual(c.execute('SELECT count(*) FROM wine_producers w LEFT JOIN primary_db.producers p ON p.id=w.producer_id WHERE p.id IS NULL').fetchone()[0],0)
            name,key,pid=c.execute('SELECT n.name,n.key,p.producer_id FROM wine_names n JOIN wine_links l ON n.id=l.wine_id JOIN wine_producers p ON p.id=l.producer_id WHERE length(n.key)>8 LIMIT 1').fetchone()
            exact='SELECT n.id,n.name,n.key,p.producer_id,l.lwin7 FROM wine_names n JOIN wine_links l ON l.wine_id=n.id JOIN wine_producers p ON p.id=l.producer_id WHERE n.key=? AND p.producer_id IN (?) ORDER BY n.id,p.producer_id LIMIT ?'
            rows=c.execute(exact,(key,pid,33)).fetchall();self.assertTrue(rows)
            self.assertTrue(all(r[3]==pid and r[2]==key for r in rows))
            self.assertEqual(c.execute(exact,(key,'unrelated',33)).fetchall(),[])
            fuzzy='SELECT n.id,n.name,n.key,p.producer_id,l.lwin7 FROM wine_fts JOIN wine_names n ON wine_fts.rowid=n.id JOIN wine_links l ON l.wine_id=n.id JOIN wine_producers p ON p.id=l.producer_id WHERE wine_fts MATCH ? AND p.producer_id IN (?) ORDER BY bm25(wine_fts),n.id,p.producer_id LIMIT ?'
            self.assertTrue(c.execute(fuzzy,('"'+key.replace('"','""')+'"',pid,33)).fetchall())
            codes=sum(len(json.loads(r[0])) for r in c.execute('SELECT lwin7 FROM wine_links'))
            self.assertEqual(codes,manifest['wine_lwin_records'])
    def test_runtime_scope_and_budget(self):
        preview=(ROOT/'app/src/main/java/com/yourapp/wine/textlab/TextLabSpatialPreview.kt').read_text()
        self.assertRegex(preview,r'LinkedWineResolver\(linkedIndex(?:,onFailureTelemetry=\{[^}]+\})?\)\.resolve\(before,wineBefore,decision,spans,checkProducerBudget(?:,policy)?\)')
        self.assertIn('wine_gazetteer_lwin7',preview)
        self.assertIn('linked_wine_candidate',preview)
        v2=(ROOT/'app/src/main/java/com/yourapp/wine/textlab/TextLabEvidencePreview.kt').read_text()
        self.assertNotIn('LinkedWineResolver',v2)
