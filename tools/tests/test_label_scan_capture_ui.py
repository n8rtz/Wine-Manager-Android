"""Source-level regression checks; CameraX rendering still requires an Android device."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
PREVIEW = ROOT / 'app/src/main/java/com/n8rtz/winetracker/ui/components/LabelScanCameraPreview.kt'
SCREEN = ROOT / 'app/src/main/java/com/n8rtz/winetracker/ui/screens/AddWineScreen.kt'
PROFILE = ROOT / 'app/src/main/java/com/n8rtz/winetracker/ui/screens/ProfileScreen.kt'
SESSION = ROOT / 'app/src/main/java/com/n8rtz/winetracker/scanner/ProductionLabelScanSession.kt'

class LabelScanCaptureUiTest(unittest.TestCase):
    def test_capture_is_shown_only_during_processing_and_live_preview_is_fallback(self):
        source = PREVIEW.read_text()
        self.assertIn('(labelScanState as? LabelScanState.Processing)?.photoPath', source)
        self.assertIn('remember(capturedPhotoPath) { mutableStateOf(false) }', source)
        self.assertIn('capturedPhotoPath?.let { capturedLabelImageRequest(context, it) }', source)
        self.assertIn('if (capturedPhotoPath != null && frameLoaded) View.INVISIBLE else View.VISIBLE', source)
        self.assertIn('onSuccess = { frameLoaded = true }', source)
        self.assertIn('onError = { frameLoaded = false }', source)
        self.assertIn('contentDescription = "Captured wine label photo"', source)
        self.assertIn('ContentScale.Crop', source)
        self.assertIn('PreviewView.ImplementationMode.COMPATIBLE', source)

    def test_photo_is_bounded_not_cached_and_cleaned_by_scan_owner(self):
        source = PREVIEW.read_text()
        self.assertIn('.data(File(photoPath))', source)
        self.assertIn('.size(1024)', source)
        for cache in ('memoryCachePolicy', 'diskCachePolicy', 'networkCachePolicy'):
            self.assertIn(f'.{cache}(CachePolicy.DISABLED)', source)
        self.assertIn('ownedFile?.let { if (it.exists() && !it.delete())', SESSION.read_text())

    def test_add_wine_has_camera_first_capture_but_no_ocr_lab_button(self):
        screen = SCREEN.read_text()
        self.assertIn('LabelScanCameraPreview(', screen)
        self.assertIn('photoPicker.launch(', screen)
        self.assertIn('BottomSheetScaffold(', screen)
        self.assertNotIn('OCR Lab', screen)
        self.assertNotIn('OcrLabActivity', screen)
        # The camera-first rework removed the form scan buttons, not the separate
        # testing section in Settings (the OCR Lab entries left with the lab itself).
        self.assertIn('Large image dataset test', PROFILE.read_text())


def test_confirm_still_renders_before_speculative_scan_starts():
    session = (ROOT / 'app/src/main/java/com/n8rtz/winetracker/scanner/ProductionLabelScanSession.kt').read_text()
    assert 'private const val SPECULATIVE_START_DELAY_MS = 500L' in session
    assert 'delay(SPECULATIVE_START_DELAY_MS)' in session
    add = (ROOT / 'app/src/main/java/com/n8rtz/winetracker/ui/screens/AddWineScreen.kt').read_text()
    assert '.size(1600)' in add
    assert '.crossfade(true)' not in add

if __name__ == '__main__': unittest.main()
