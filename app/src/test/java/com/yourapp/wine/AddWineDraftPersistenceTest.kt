package com.yourapp.wine

import android.app.Application
import com.n8rtz.winetracker.scanner.AddWineDraft
import com.n8rtz.winetracker.scanner.AddWineDraftStore
import com.n8rtz.winetracker.scanner.PendingLabelScanStore
import com.yourapp.wine.capture.ScanReport
import com.yourapp.wine.fill.FieldId
import com.yourapp.wine.fill.UiState
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AddWineDraftPersistenceTest {
    private val app get() = RuntimeEnvironment.getApplication()
    @Before fun clean() {
        app.getSharedPreferences("add-wine-draft-v1", 0).edit().clear().commit()
        app.getSharedPreferences("production-label-scan-v1", 0).edit().clear().commit()
    }
    @Test fun draftSurvivesRecreationAndProtectsManualEdits() {
        val draft = AddWineDraft(maker = "My winery", name = "My wine", vintage = "2020", country = "France",
            quantity = "2", price = "12.50", edited = setOf(FieldId.PRODUCER, FieldId.VINTAGE))
        assertTrue(AddWineDraftStore(app).save(draft, synchronous = true))
        val restored = AddWineDraftStore(app).load()
        assertEquals(draft, restored)
        assertTrue(restored.formState().fields.getValue(FieldId.PRODUCER).edited)
        assertTrue(restored.formState().fields.getValue(FieldId.VINTAGE).edited)
        assertFalse(restored.formState().fields.getValue(FieldId.COUNTRY).edited)
        AddWineDraftStore(app).clear()
        assertEquals(AddWineDraft(), AddWineDraftStore(app).load())
    }
    @Test fun resultAndActiveFilePersistUntilAcknowledged() {
        val store = PendingLabelScanStore(app)
        val file = File(app.cacheDir, "pending-label-test.jpg")
        val report = ScanReport.red("Cannot read").let { base ->
            val producer = base.fill.fields.getValue(FieldId.PRODUCER).copy(value="Winery", state=UiState.YELLOW)
            base.copy(fill=base.fill.copy(fields=base.fill.fields + (FieldId.PRODUCER to producer), overallHint=null))
        }
        assertTrue(store.saveActive(42, file, "CAMERA"))
        assertTrue(store.saveResult(42, report))
        val restored = PendingLabelScanStore(app)
        assertEquals(file.absolutePath, restored.active()?.path)
        val result = restored.result()!!
        assertEquals(42L, result.requestId)
        assertEquals("Winery", result.result.fill.fields.getValue(FieldId.PRODUCER).value)
        assertEquals(UiState.YELLOW, result.result.fill.fields.getValue(FieldId.PRODUCER).state)
        restored.clearResult(41)
        assertNotNull(restored.result())
        restored.clearResult(42)
        restored.clearActive()
        assertNull(restored.result())
        assertNull(restored.active())
    }
}
