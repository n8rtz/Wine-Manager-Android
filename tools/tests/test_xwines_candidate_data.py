import importlib.util
import json
import sqlite3
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools/producer-sources'))
ASSET = ROOT / 'app/src/debug/assets/producer-gazetteer-v1'
MAIN = ROOT / 'app/src/main/assets/producer-gazetteer-v1'
REFRESH_REPORT = ASSET / 'supplemental-source-refresh.json'
spec = importlib.util.spec_from_file_location('refresh_open_candidates', ROOT / 'tools/producer-sources/refresh_open_candidates.py')
refresh = importlib.util.module_from_spec(spec)
spec.loader.exec_module(refresh)


class XWinesCandidateDataTests(unittest.TestCase):
    def test_snapshot_provenance_and_incremental_coverage(self):
        report = json.loads(REFRESH_REPORT.read_text())
        manifest = json.loads((MAIN / 'manifest.json').read_text())
        source = report['xwines']
        layer = manifest['support']['xwines']
        self.assertEqual(source['xwines_license'], 'CC0-1.0')
        self.assertEqual(source['dataset']['grand_total_wines'], 100646)
        self.assertEqual(source['dataset']['winery_rows'], 30510)
        self.assertEqual(source['source_rows'], 30510)
        self.assertEqual(source['candidates'], 19085)
        self.assertEqual(source['unique_candidate_keys'], 18945)
        self.assertEqual(source['new_distinct_names_vs_current_sources'], 18006)
        self.assertEqual(layer['candidates'], source['candidates'])
        self.assertEqual(layer['sha256'], refresh.db_hash(ASSET / layer['file']))
        self.assertEqual(source['role'], 'unverified winery/producer/brand name candidate; no country or aliases inferred')
        self.assertIsNone(source['atlas_repo_license'])
        self.assertEqual(source['atlas_commit'], layer['source_commit'])
        self.assertIn('X-Wines', manifest['support_attribution'])

    def test_candidate_database_is_separate_and_does_not_duplicate_primary_keys(self):
        manifest = json.loads((MAIN / 'manifest.json').read_text())
        layer = manifest['support']['xwines']
        with sqlite3.connect(ASSET / layer['file']) as db, sqlite3.connect(MAIN / 'producers.sqlite') as primary:
            self.assertEqual(db.execute('select count(*) from support').fetchone()[0], layer['support_rows'])
            self.assertEqual(db.execute('select count(*) from candidates').fetchone()[0], layer['candidates'])
            self.assertEqual(db.execute('select count(*) from candidate_names').fetchone()[0], layer['candidates'])
            candidate_keys = {row[0] for row in db.execute('select distinct key from candidates')}
            primary_keys = {row[0] for row in primary.execute('select distinct key from producers')}
            self.assertFalse(candidate_keys & primary_keys)
            name, key = db.execute('select name,key from candidates where length(key)>8 limit 1').fetchone()
            self.assertTrue(db.execute('select 1 from candidates where key=? limit 1', (key,)).fetchone())
            trigram = next(key[i:i+3] for i in range(len(key)-2) if not any(c.isspace() for c in key[i:i+3]))
            self.assertTrue(db.execute('select 1 from candidate_names where candidate_names match ? limit 1', (f'"{trigram}"',)).fetchone())
            self.assertTrue(name)

    def test_builder_preserves_duplicate_names_as_ambiguous_rows(self):
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp) / 'candidate.sqlite'
            counts = refresh.create_xwines_db(output, ['Winery Example', 'Winery Example', 'Other Winery', 'Primary Winery'], {'primary winery'})
            self.assertEqual((counts['source_rows'], counts['candidates'], counts['unique_candidate_keys']), (4, 3, 2))
            with sqlite3.connect(output) as db:
                self.assertEqual(db.execute('select count(*) from candidates where key="winery example"').fetchone()[0], 2)
                self.assertEqual(db.execute('select count(*) from support').fetchone()[0], 4)

    def test_payload_parser_reads_json_without_executing_archive_code(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / 'atlas.zip'
            commit = '1' * 40
            payload = {'allWineriesCount': 2, 'grand_total_wines': 100646, 'grand_total_wineries': 2,
                       'loaded_at': 'fixture', 'countries': [], 'regions': [],
                       'wine_table': {'wineries': ['Domaine One', 'Estate Two']}}
            html = '<script>window.__XWINES_PREBAKED__=' + json.dumps(payload) + ';window.should_not_run=true;</script>'
            with zipfile.ZipFile(path, 'w') as archive:
                archive.writestr(f'Xwines-Atlas-{commit}/index.html', html)
            names, summary = refresh.extract_xwines_names({'archive_path': path, 'repo': 'JamesOH5/Xwines-Atlas', 'commit': commit})
            self.assertEqual(names, ['Domaine One', 'Estate Two'])
            self.assertEqual(summary['unique_raw_names'], 2)

    def test_latest_openstreetmap_snapshot_replaces_support_not_primary(self):
        report = json.loads(REFRESH_REPORT.read_text())
        manifest = json.loads((MAIN / 'manifest.json').read_text())
        osm = report['osm']
        self.assertEqual(osm['commit'], manifest['support']['osm']['source_commit'])
        self.assertGreaterEqual(osm['records'], 15000)
        self.assertGreaterEqual(osm['records'], 15000)
        self.assertGreaterEqual(osm['new_osm_ids'], 0)
        self.assertGreaterEqual(osm['new_exact_lwin_support_keys'], 0)
        self.assertEqual(manifest['records'], 33160)
        self.assertEqual(manifest['database_sha256'], refresh.db_hash(MAIN / 'producers.sqlite'))


if __name__ == '__main__':
    unittest.main()
