"""Static guards for the retired debug entry points and the retained raw-recognition path."""
import pathlib,unittest
ROOT=pathlib.Path(__file__).resolve().parents[2]
class TextLabBoundaryTests(unittest.TestCase):
    def test_removed_debug_activity_is_not_declared_or_linked(self):
        name='com.yourapp.wine.textlab.OcrTextLabActivity'
        debug=(ROOT/'app/src/debug/AndroidManifest.xml').read_text()
        self.assertNotIn(name,debug)
        self.assertNotIn('OcrLabActivity',debug)
        main=(ROOT/'app/src/main/AndroidManifest.xml').read_text()
        self.assertNotIn(name,main)
        profile=(ROOT/'app/src/main/java/com/n8rtz/winetracker/ui/screens/ProfileScreen.kt').read_text()
        self.assertNotIn(name,profile)
        self.assertNotIn('OcrLabActivity',profile)
    def test_manual_crop_uses_shared_gate_and_only_raw_mlkit(self):
        source=(ROOT/'app/src/main/java/com/yourapp/wine/textlab/TextLabRunner.kt').read_text()
        self.assertIn('scanner.withBenchmarkEngines',source)
        self.assertIn('it.id=="mlkit" && it.available',source)
        self.assertIn('engine.recognize(crop,',source)
        self.assertIn('workspace.release(source)',source)
        self.assertIn('box.toOriginal(',source)
        for forbidden in ('fills.fill(', 'BenchmarkScoring', 'FieldExtractor', 'Preprocess(', 'expected'):
            self.assertNotIn(forbidden,source)
