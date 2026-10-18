"""Static debug entry-point/raw-recognition guards; not a substitute for Android UI tests."""
import pathlib,unittest,xml.etree.ElementTree as ET
ROOT=pathlib.Path(__file__).resolve().parents[2]
class TextLabBoundaryTests(unittest.TestCase):
    def test_activity_is_debug_only_and_private(self):
        name='com.yourapp.wine.textlab.OcrTextLabActivity'
        attr='{http://schemas.android.com/apk/res/android}'
        debug=ET.parse(ROOT/'app/src/debug/AndroidManifest.xml')
        entry=next(a for a in debug.findall('.//activity') if a.get(attr+'name')==name)
        self.assertEqual(entry.get(attr+'exported'),'false')
        main=(ROOT/'app/src/main/AndroidManifest.xml').read_text()
        self.assertNotIn(name,main)
        profile=(ROOT/'app/src/main/java/com/n8rtz/winetracker/ui/screens/ProfileScreen.kt').read_text()
        self.assertIn(name,profile.split('if (BuildConfig.DEBUG) {',1)[1])
    def test_manual_crop_uses_shared_gate_and_only_raw_mlkit(self):
        source=(ROOT/'app/src/debug/java/com/yourapp/wine/textlab/TextLabRunner.kt').read_text()
        self.assertIn('scanner.withBenchmarkEngines',source)
        self.assertIn('it.id=="mlkit" && it.available',source)
        self.assertIn('engine.recognize(crop,',source)
        self.assertIn('workspace.release(source)',source)
        self.assertIn('box.toOriginal(',source)
        for forbidden in ('fills.fill(', 'BenchmarkScoring', 'FieldExtractor', 'Preprocess(', 'expected'):
            self.assertNotIn(forbidden,source)
