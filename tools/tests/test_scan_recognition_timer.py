"""Developer-option scan recognition timer: settings toggle + Add Wine display."""
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
APP = ROOT / 'app/src/main/java/com/n8rtz/winetracker'


class ScanRecognitionTimerTests(unittest.TestCase):
    def test_toggle_setting_is_persisted(self):
        store = (APP / 'scanner/ScanTimerSettings.kt').read_text()
        self.assertIn('object ScanTimerSettings', store)
        self.assertIn('"show_recognition_timer"', store)
        self.assertIn('fun isEnabled(context: Context): Boolean', store)
        self.assertIn('fun setEnabled(context: Context, enabled: Boolean)', store)
        self.assertIn('getBoolean(KEY_SHOW_RECOGNITION_TIMER, false)', store)
        self.assertIn('fun formatDuration(elapsedMs: Long): String', store)

    def test_settings_has_developer_options_toggle(self):
        profile = (APP / 'ui/screens/ProfileScreen.kt').read_text()
        self.assertIn('"Developer options"', profile)
        self.assertIn('Text("Show scan recognition timer")', profile)
        self.assertIn('ScanTimerSettings.isEnabled(context)', profile)
        self.assertIn('ScanTimerSettings.setEnabled(context, enabled)', profile)
        self.assertIn('checked = showScanTimer', profile)

    def test_completion_popup_lists_each_step_with_copy_button(self):
        add = (APP / 'ui/screens/AddWineScreen.kt').read_text()
        store = (APP / 'scanner/ScanTimerSettings.kt').read_text()
        self.assertIn('fun formatDurationWithMs(elapsedMs: Long): String', store)
        # Popup opens when a scan completes and the toggle is on.
        self.assertIn('if (showScanTimer) showScanTimingsPopup = true', add)
        self.assertIn('AlertDialog(', add)
        self.assertIn('title = { Text("Recognition time") }', add)
        # Every diagnostics stage is listed, plus a total.
        self.assertIn('report.diagnostics.stages.map { timing ->', add)
        self.assertIn('"Total: ${ScanTimerSettings.formatDurationWithMs(report.elapsedMs)}"', add)
        self.assertIn('private fun scanStageLabel(', add)
        # Copy button puts the same text on the clipboard.
        self.assertIn('clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(shareText))', add)
        self.assertIn('Text("Copy to clipboard")', add)

    def test_timer_starts_at_photo_confirmation_not_shutter(self):
        session = (APP / 'scanner/ProductionLabelScanSession.kt').read_text()
        # captured() runs at the confirm tap; it must stamp the confirm
        # time as the recognition start, not forward the shutter time.
        self.assertIn('val confirmedMs = SystemClock.elapsedRealtime()', session)
        self.assertIn('LabelScanState.Processing(id, file.absolutePath, confirmedMs)', session)
        self.assertIn('launchPhotoScan(app, id, started, ScanInputSource.CAMERA, file)', session)
        self.assertNotIn('LabelScanState.Processing(id, file.absolutePath, current.shutterMs)', session)

    def test_add_wine_shows_live_and_final_timer(self):
        add = (APP / 'ui/screens/AddWineScreen.kt').read_text()
        self.assertIn('val showScanTimer = ScanTimerSettings.isEnabled(context)', add)
        # Live ticking timer alongside the progress step.
        self.assertIn('DrawerScanProgress(labelScanState, showScanTimer)', add)
        self.assertIn('ScanTimerSettings.formatDuration(nowMs - startedMs)', add)
        # Final total comes from the report's whole-recognition elapsed time.
        self.assertIn('"Recognition took ${ScanTimerSettings.formatDuration(report.elapsedMs)}"', add)


if __name__ == '__main__':
    unittest.main()
