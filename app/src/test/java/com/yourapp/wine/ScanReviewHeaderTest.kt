package com.yourapp.wine

import android.app.Application
import android.content.ClipboardManager
import android.content.ClipDescription
import androidx.core.graphics.ColorUtils
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.yourapp.wine.capture.ScanReport
import com.yourapp.wine.fill.ConfidenceContext
import com.yourapp.wine.fill.FieldId
import com.yourapp.wine.fill.FillEngine
import com.yourapp.wine.kb.FixtureKnowledgeBase
import com.yourapp.wine.fill.UiState
import com.yourapp.wine.ui.ScanFieldStatus
import com.yourapp.wine.ui.ScanDiagnosticsPanel
import com.yourapp.wine.ui.LabelScanActions
import com.yourapp.wine.diagnostics.ScanEnvironment
import com.yourapp.wine.ui.ScanFormState
import com.yourapp.wine.ui.ScanReviewHeader
import com.yourapp.wine.ui.scanFieldStatusColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** UI contracts only: synthetic field results do not execute CameraX, ML Kit or native OpenCV. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ScanReviewHeaderTest {
    @get:Rule val compose = createComposeRule()

    private val report = ScanReport.red("Check the label before saving").let { empty ->
        empty.copy(fill = empty.fill.copy(fields = empty.fill.fields + mapOf(
            FieldId.PRODUCER to empty.fill.fields.getValue(FieldId.PRODUCER).copy(
                value = "Domaine Petit Nuage", state = UiState.YELLOW, reason = "OCR-only producer"),
            FieldId.WINE_NAME to empty.fill.fields.getValue(FieldId.WINE_NAME).copy(
                value = "Pinot Noir", state = UiState.YELLOW, reason = "OCR-only wine name")
        )), tokens = listOf(LogicChecks.token("Domaine Petit Nuage")), origW = 840, origH = 1120)
    }
    private val initialForm = ScanFormState().apply(report.fill, FieldId.entries.associateWith { "" })

    @Test fun wireframeIsHiddenUntilExplicitlyRequested() {
        compose.setContent {
            MaterialTheme { ScanReviewHeader(report, initialForm, 1, allowDebugOverlay = true, onRetake = {}) }
        }
        compose.onNodeWithText("Review scanned details").assertIsDisplayed()
        compose.onNodeWithText("2 of 6 label fields read. Check the suggestions in the form below.").assertIsDisplayed()
        compose.onNodeWithTag("scan_debug_details").assertDoesNotExist()
        compose.onNodeWithText("Show scan details").performClick()
        compose.onNodeWithTag("scan_debug_details").assertExists()
        compose.onNodeWithTag("scan_debug_overlay").assertDoesNotExist()
        compose.onNodeWithText("Copy diagnostics").assertExists()
        compose.onNodeWithText("Hide scan details").performClick()
        compose.onNodeWithTag("scan_debug_details").assertDoesNotExist()
    }

    @Test fun identicalRetakeStillResetsTheDiagnosticsPanel() {
        var scanId by mutableStateOf(1L)
        compose.setContent {
            MaterialTheme { ScanReviewHeader(report, initialForm, scanId, allowDebugOverlay = true, onRetake = {}) }
        }
        compose.onNodeWithText("Show scan details").performClick()
        compose.onNodeWithTag("scan_debug_details").assertExists()
        compose.runOnIdle { scanId = 2L } // Same result object, different capture request.
        compose.onNodeWithTag("scan_debug_details").assertDoesNotExist()
        compose.onNodeWithText("Show scan details").assertIsDisplayed()
    }

    @Test fun productionReviewDoesNotOfferDebugWireframe() {
        compose.setContent {
            MaterialTheme { ScanReviewHeader(report, initialForm, 1, allowDebugOverlay = false, onRetake = {}) }
        }
        compose.onNodeWithText("Show scan details").performClick()
        compose.onNodeWithText("Copy diagnostics").assertExists()
        compose.onNodeWithText("Show OCR boxes").assertDoesNotExist()
        compose.onNodeWithTag("scan_debug_overlay").assertDoesNotExist()
    }

    @Test fun rejectedCaptureOffersManualEntryAndTheExistingRetakeCallback() {
        var retakes = 0
        val rejected = ScanReport.red("Hold steady")
        val form = ScanFormState().apply(rejected.fill, FieldId.entries.associateWith { "" })
        compose.setContent {
            MaterialTheme { ScanReviewHeader(rejected, form, 1, onRetake = { retakes++ }) }
        }
        compose.onNodeWithText("No label details read").assertIsDisplayed()
        compose.onNodeWithText("Retake the photo, or enter the wine details in the form below.").assertIsDisplayed()
        compose.onNodeWithText("Retake photo").performClick()
        compose.runOnIdle { assertEquals(1, retakes) }
    }

    @Test fun yellowStillNeedsOneTapConfirmationAndKeepsManualEdits() {
        var form by mutableStateOf(initialForm.edit(FieldId.WINE_NAME, "My wine name"))
        compose.setContent {
            MaterialTheme {
                Column {
                    ScanReviewHeader(report, form, 1, onRetake = {})
                    ScanFieldStatus(form.fields.getValue(FieldId.PRODUCER),
                        onConfirm = { form = form.confirm(FieldId.PRODUCER) }, onRetake = {})
                }
            }
        }
        compose.onNodeWithText("1 field needs your confirmation below.").assertIsDisplayed()
        compose.onNodeWithText("Confirm").performClick()
        compose.onNodeWithText("1 field needs your confirmation below.").assertDoesNotExist()
        compose.runOnIdle {
            assertFalse(form.hasPendingConfirmation)
            assertEquals("My wine name", form.fields.getValue(FieldId.WINE_NAME).value)
        }
    }
    private fun locationOnlyReport(): ScanReport {
        // CALIFORNIA is visible in the screenshot; the weak identity below is hypothetical, not a measured phone result.
        val readings = listOf(LogicChecks.token("EXAMPLE WINERY", score = .45f),
            LogicChecks.token("CALIFORNIA", score = .84f, y = .6f))
        return ScanReport.red("").copy(tokens = readings, origW = 840, origH = 1120,
            fill = FillEngine(FixtureKnowledgeBase()).fill(readings, 2026, ConfidenceContext(labelFound = false)))
    }

    @Test fun locationOnlyReadingDoesNotImplyTheWineWasIdentified() {
        val partial = locationOnlyReport()
        val form = ScanFormState().apply(partial.fill, FieldId.entries.associateWith { "" })
        compose.setContent {
            MaterialTheme { ScanReviewHeader(partial, form, 1, onRetake = {}) }
        }
        compose.onNodeWithText("Only partial label details read").assertIsDisplayed()
        compose.onNodeWithText("The scan did not read a producer or wine name. Check the partial details below, then retake or enter the identity manually.").assertExists()
        compose.onNodeWithText("Review scanned details").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(UiState.RED, form.fields.getValue(FieldId.PRODUCER).state)
            assertEquals("", form.fields.getValue(FieldId.PRODUCER).value)
            assertEquals("California", form.fields.getValue(FieldId.REGION).value)
        }
    }

    @Test fun optInDiagnosticsShowMappedReadingsBeforeTheConfidenceFilter() {
        val partial = locationOnlyReport()
        val form = ScanFormState().apply(partial.fill, FieldId.entries.associateWith { "" })
        compose.setContent {
            MaterialTheme { ScanReviewHeader(partial, form, 1, allowDebugOverlay = true, onRetake = {}) }
        }
        compose.onNodeWithText("EXAMPLE WINERY", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Show scan details").performClick()
        waitForReport()
        compose.onNodeWithText("text=\"EXAMPLE WINERY\"", substring = true).assertExists()
        compose.onNodeWithText("score=0.450", substring = true).assertExists()
        compose.onNodeWithText("score=0.840", substring = true).assertExists()
    }

    @Test fun semanticStatusColorsHaveReadableContrastOnTheAppFormBackgrounds() {
        val backgrounds = listOf(Color(0xff121212) to true, Color(0xff1e1e1e) to true,
            Color(0xfff8f9fa) to false, Color.White to false)
        for ((background, dark) in backgrounds) for (state in UiState.entries) {
            val foreground = scanFieldStatusColor(state, dark,
                if (dark) Color(0xffffb4ab) else Color(0xffb3261e))
            val tinted = foreground.copy(alpha = .08f).compositeOver(background)
            val contrast = ColorUtils.calculateContrast(foreground.toArgb(), tinted.toArgb())
            assertTrue("$state dark=$dark contrast=$contrast", contrast >= 4.5)
        }
    }

    private fun waitForReport() {
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("WINE-SCAN-DIAGNOSTICS v1", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun copyButtonExportsThePlainTextReportToTheClipboard() {
        val clipboard = RuntimeEnvironment.getApplication().getSystemService(ClipboardManager::class.java)
        clipboard.clearPrimaryClip()
        compose.setContent {
            MaterialTheme { ScanDiagnosticsPanel(locationOnlyReport(), 9, allowDebugOverlay = false,
                environment = ScanEnvironment(buildCommit = "test123", model = "test device")) }
        }
        waitForReport()
        compose.onNodeWithText("Copy diagnostics").performClick()
        compose.onNodeWithText("Diagnostics copied").assertExists()
        compose.runOnIdle {
            val clip = clipboard.primaryClip!!
            val text = clip.getItemAt(0).text.toString()
            assertTrue(text.startsWith("WINE-SCAN-DIAGNOSTICS v1"))
            assertTrue("commit=test123" in text && "CALIFORNIA" in text)
            assertTrue(clip.description.extras!!.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE))
        }
    }

    @Test fun choosingPhotoDoesNotInvokeTheCameraAction() {
        var cameraRequests = 0
        var photoRequests = 0
        compose.setContent {
            MaterialTheme { LabelScanActions(false, null, { cameraRequests++ }, { photoRequests++ }) }
        }
        compose.onNodeWithText("Choose label").performClick()
        compose.runOnIdle { assertEquals(0, cameraRequests); assertEquals(1, photoRequests) }
    }

    @Test fun inputActionsAreDisabledWhileACopyOrScanIsBusy() {
        compose.setContent {
            MaterialTheme { LabelScanActions(true, "Opening selected photo…", {}, {}) }
        }
        compose.onNodeWithText("Choose label").assertIsNotEnabled()
        compose.onNodeWithText("Scan label").assertIsNotEnabled()
        compose.onNodeWithText("Opening selected photo…").assertIsDisplayed()
    }

}
