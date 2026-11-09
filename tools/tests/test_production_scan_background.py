"""Lifecycle and clean production form contracts; these do not emulate a locked phone."""
from pathlib import Path
import unittest

ROOT=Path(__file__).resolve().parents[2]
JAVA=ROOT/'app/src/main/java'
SCREEN=JAVA/'com/n8rtz/winetracker/ui/screens/AddWineScreen.kt'
PREVIEW=JAVA/'com/n8rtz/winetracker/ui/components/LabelScanCameraPreview.kt'
SESSION=JAVA/'com/n8rtz/winetracker/scanner/ProductionLabelScanSession.kt'
SERVICE=JAVA/'com/n8rtz/winetracker/scanner/LabelScanService.kt'
STORE=JAVA/'com/n8rtz/winetracker/scanner/PendingLabelScanStore.kt'

class ProductionScanBackgroundTest(unittest.TestCase):
    def test_screen_lifecycle_only_cancels_pending_shutter_not_reading(self):
        preview=PREVIEW.read_text()
        self.assertIn('ON_STOP) scannerViewModel.cancelPendingLabelCapture()',preview)
        self.assertNotIn('ON_STOP) scannerViewModel.cancelActiveLabelScan()',preview)
        screen=SCREEN.read_text()
        self.assertIn('ON_STOP && !isChangingConfigurations(context)',screen)
        self.assertIn('scannerViewModel.cancelPendingLabelCapture()',screen)
        self.assertNotIn('cancelActiveLabelScan()',screen)
        source=SESSION.read_text()
        self.assertIn('CoroutineScope(SupervisorJob() + Dispatchers.Default)',source)
        self.assertIn('scope.launch(start = CoroutineStart.LAZY)',source)
        self.assertIn('if (owns) launched.start() else launched.cancel()',source)
        self.assertIn('ProductionLabelScanner.scan(app, photo.file, started, source)',source)
        self.assertIn('(mutable.value as? LabelScanState.CapturingPhoto)?.requestId',source)
    def test_foreground_lifetime_and_private_result_recovery(self):
        service=SERVICE.read_text(); session=SESSION.read_text(); store=STORE.read_text()
        self.assertIn('startForeground(',service)
        self.assertIn('PARTIAL_WAKE_LOCK',service)
        self.assertIn('START_STICKY',service)
        self.assertIn('ProductionLabelScanSession.recover',service)
        self.assertIn('PendingLabelScanStore(app).saveActive',session)
        self.assertIn('PendingLabelScanStore(app).saveResult',session)
        self.assertIn('.commit()',store)
        self.assertIn('foregroundServiceType="specialUse"', (ROOT/'app/src/main/AndroidManifest.xml').read_text())
    def test_clean_form_and_draft_before_scan_acknowledgement(self):
        screen=SCREEN.read_text()
        for forbidden in ('ScanReviewHeader(', 'ScanFieldStatus(', 'ScanFieldChoices(', 'scanForm.hasPendingConfirmation',
                          'Review scanned details', 'RED · No fields filled'):
            self.assertNotIn(forbidden,screen)
        self.assertIn('scanForm = scanForm.apply(review.result.fill, values)',screen)
        self.assertIn('draftStore.save(currentDraft(), synchronous = true)',screen)
        self.assertIn('scannerViewModel.dismissLabelScan(review.requestId)',screen)
        self.assertIn('draftStore.clear()',screen)
        self.assertIn('onWineAdded()',screen)

if __name__=='__main__': unittest.main()
