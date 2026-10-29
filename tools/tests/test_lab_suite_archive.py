import csv
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
import zipfile

TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))
from lab_suite_archive import pack_csv, read_rows, read_many, write_json_package, verify, unpack_csv


class SuiteArchiveTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        meta = dict(recent_rank='1', run_id='full-run-id', date='2026-10-29', time='12:00',
                    build_number='build [configuration]', build_commit='abc', lab_modules='x+y', corpus_key='same')
        self.rows = [dict(meta, row_type='run_summary', total_correct='2', complete='1')]
        for n in range(12):
            diagnostic = dict(phrase_id='8', candidate_values='{WINE_NAME=Réserve "A", B}',
                              source_observations='[0, 2]', base_rank='55.0', adjusted_rank='61.0',
                              rank_delta='6.0', sequence=str(n), event_at_ms=str(1000+n), candidate='spatial_lab',
                              post_processing_version='v1', spatial_source_count='4',
                              unknown_future_data={'arrays': [None, False, 1.25], 'unicode': '日本語\ntext'})
            self.rows.append(dict(meta, row_type='run_decision', image='label.jpg', ocr_ref='hash',
                                  stage='spatial_evidence_hypothesis', diagnostic_json=json.dumps(diagnostic)))
        self.rows.append(dict(meta, row_type='wine_field', image='label.jpg', review_number='7',
                              field='wine_name', recognized='Réserve "A", B', expected='Réserve "A", B',
                              outcome='correct_value', reference_status='expected_value'))
        self.source = self.root/'source.csv'
        keys = list(dict.fromkeys(key for row in self.rows for key in row))
        with self.source.open('w', newline='', encoding='utf-8') as stream:
            writer = csv.DictWriter(stream, fieldnames=keys)
            writer.writeheader(); writer.writerows(self.rows)
        self.target = self.root/'suite.zip'
        pack_csv([self.source], self.target)

    def test_every_record_and_unknown_nested_value_round_trips(self):
        restored = list(read_rows(self.target))
        self.assertEqual(len(restored), len(self.rows))
        for original, actual in zip(self.rows, restored):
            for key, value in original.items():
                if key == 'diagnostic_json':
                    self.assertEqual(json.loads(value), json.loads(actual[key]))
                else:
                    self.assertEqual(value, actual[key])
        unpack_csv(self.target, self.root/'restored.csv')
        self.assertEqual(len(list(read_rows(self.root/'restored.csv'))), len(self.rows))

    def test_metadata_candidate_and_payload_dedup_with_occurrences_preserved(self):
        with zipfile.ZipFile(self.target) as archive:
            manifest = verify(archive)
            counts = manifest['dictionary_counts']
            self.assertEqual(counts['runs.jsonl'], 1)
            self.assertEqual(counts['candidates.jsonl'], 1)
            self.assertEqual(counts['common.jsonl'], 1)
            events = [json.loads(line) for line in archive.read('events.jsonl').splitlines()]
            decisions = [e for e in events if 'd' in e]
            self.assertEqual(len({e['d'] for e in decisions}), 1)
            self.assertEqual(len({e['o']['sequence'] for e in decisions}), 12)
        self.assertLess(self.target.stat().st_size, self.source.stat().st_size)

    def test_scores_can_be_read_without_expanding_diagnostics(self):
        rows = list(read_rows(self.target, scores_only=True))
        self.assertEqual([r['row_type'] for r in rows], ['run_summary', 'wine_field'])
        self.assertEqual(rows[-1]['review_number'], '7')
        self.assertEqual(rows[-1]['run_id'], 'full-run-id')

    def test_integrity_failure_is_reported(self):
        broken = self.root/'broken.zip'
        with zipfile.ZipFile(self.target) as source, zipfile.ZipFile(broken, 'w') as target:
            for name in source.namelist():
                target.writestr(name, b'corrupted' if name == 'events.jsonl' else source.read(name))
        with self.assertRaisesRegex(ValueError, 'Integrity check failed'):
            list(read_rows(broken))

    def test_uploadable_json_parts_round_trip_and_checksums(self):
        outputs = write_json_package(self.target, self.root/'upload.json', max_bytes=2200)
        self.assertGreater(len(outputs), 3)
        restored = list(read_many([outputs[0]]))
        expected = list(read_rows(self.target))
        self.assertEqual(restored, expected)
        for part in outputs[2:]:
            self.assertLessEqual(part.stat().st_size, 2200)
            json.loads(part.read_text())
        # Convenience scores need not be uploaded.
        outputs[1].unlink()
        self.assertEqual(list(read_many([outputs[0]])), expected)
        outputs[-1].write_text('{}')
        with self.assertRaisesRegex(ValueError, 'Integrity check failed'):
            list(read_many([outputs[0]]))

    def test_missing_json_parts_cannot_silently_pass(self):
        outputs = write_json_package(self.target, self.root/'upload.json', max_bytes=2200)
        with self.assertRaisesRegex(ValueError, 'Include the JSON manifest'):
            list(read_many([outputs[2]]))
        outputs[-1].unlink()
        with self.assertRaisesRegex(ValueError, 'Missing package file'):
            list(read_many([outputs[0]]))

    def test_raw_diagnostics_use_native_json_and_never_the_csv_splitter(self):
        root = TOOLS.parent/'app/src/main/java/com/yourapp/wine/phonecorpus'
        vm = (root/'WineAppLabelsViewModel.kt').read_text()
        ui = (root/'WineAppLabelsActivity.kt').read_text()
        self.assertNotIn('writeRawCsv', vm)
        self.assertIn('Prepare raw diagnostics JSON export', ui)
        self.assertIn('LabSuiteArchive.write(target, rawExportRows())', vm)
        body = vm.split('private fun rawExportRows()', 1)[1].split('private fun diagnosticHash', 1)[0]
        self.assertIn('listOf("tokens", "engine_timings", "trace", "pipelines", "pipeline_funnel")', body)
        self.assertIn('"diagnostic_json" to row.toString()', body)
        self.assertIn('"record_index" to index.toString()', body)
        self.assertIn('for ((mode, frozen) in predictionsByMode())', body)
        self.assertIn('"frozen-baseline"', body)
        self.assertIn('scorer.build(frozen, mode).csv.filter', body)
        self.assertNotIn('row.optString(it)', body)
        routing = vm.split('val parts = if (jsonDiagnostics)', 1)[1].split('previousParts.mapNotNull', 1)[0]
        json_branch, csv_branch = routing.split('} else {', 1)
        self.assertNotIn('CsvUploadParts', json_branch)
        self.assertIn('writeRecentBuildsCsv(target, recentRuns)', csv_branch)
        self.assertIn('writeScoredCsv(target)', csv_branch)
        self.assertIn('CsvUploadParts.split', csv_branch)

    def test_android_archive_path_and_mime_are_wired(self):
        root = TOOLS.parent/'app/src/main/java/com/yourapp/wine/phonecorpus'
        vm = (root/'WineAppLabelsViewModel.kt').read_text()
        ui = (root/'WineAppLabelsActivity.kt').read_text()
        self.assertIn('LabSuiteArchive.write(target,recentBuildRows(completedRuns,suite))', vm)
        self.assertIn('LabSuiteArchive.write(target,recentBuildRows(runs,suite))', vm)
        self.assertIn('CreateDocument("application/json")', ui)
        self.assertIn('exportJson.launch(file.name)', ui)
        writer = (root/'LabSuiteArchive.kt').read_text()
        for expected in ['writeJsonParts(target,work', '"candidates.jsonl"', '"common.jsonl"', '"events.jsonl"',
                         '-manifest.json', '"sha256"', '"scores.csv"']:
            self.assertIn(expected, writer)
