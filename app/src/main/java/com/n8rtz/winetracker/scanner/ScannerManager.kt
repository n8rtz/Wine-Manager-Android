package com.n8rtz.winetracker.scanner

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.yourapp.wine.capture.ScanReport
import com.yourapp.wine.capture.ProductionLabelScanner
import com.yourapp.wine.diagnostics.ScanInputSource
import com.yourapp.wine.fill.FieldId
import com.yourapp.wine.fill.UiState
import com.yourapp.wine.merge.LineBuilder
import com.yourapp.wine.merge.TokenMerger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Compatibility facade for older callers. Add Wine uses ScanReport directly to retain per-field states. */
class ScannerManager(context: Context) {
    private val appContext = context.applicationContext

    /** Original coordinates are EXIF-upright JPEG pixels; every lookup is on-device, never a wine-database API. */
    suspend fun scanImage(file: File): ScannedWine {
        val started = SystemClock.elapsedRealtime()
        return withContext(Dispatchers.Default) { scanLabel(file, started).toLegacyWine() }
    }

    /** EXIF-upright original JPEG pixels in / normalized original+label boxes out; deadline includes startup. */
    suspend fun scanLabel(file: File, shutterMs: Long = SystemClock.elapsedRealtime()): ScanReport = withContext(Dispatchers.Default) {
        try {
            ProductionLabelScanner.scan(appContext,file,shutterMs,ScanInputSource.FILE)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: OutOfMemoryError) { ScanReport.red("Memory pressure — enter manually") }
        catch (_: LinkageError) { ScanReport.red("Native scanner unavailable — enter manually") }
        catch (_: Exception) { ScanReport.red("Could not read this photo — retake or enter manually") }
    }

    /** No coordinates: the retired unbounded Bitmap API cannot guarantee ownership; use a captured JPEG instead. */
    @Deprecated("Use scanImage(File) or the Add Wine CameraX scanner to enforce bounded bitmap ownership")
    suspend fun scanImage(bitmap: Bitmap): ScannedWine = ScannedWine(
        needsReview = true, captureQuality = "Use the Add Wine camera scanner or enter details manually")

    /** No coordinates: clients are app-scoped; closing a facade must not tear down another active scan. */
    fun close() = Unit
}

/** Token evidence retains original/rectified boxes; RED suggestions are omitted from the legacy editable result. */
internal fun ScanReport.toLegacyWine(): ScannedWine {
    fun field(id: FieldId): String? = fill.fields.getValue(id).takeIf { it.state != UiState.RED }?.value?.takeIf { it.isNotBlank() }
    return ScannedWine(maker = field(FieldId.PRODUCER), name = field(FieldId.WINE_NAME), vintage = field(FieldId.VINTAGE),
        country = field(FieldId.COUNTRY), region = field(FieldId.REGION), subregion = field(FieldId.SUBREGION),
        rawText = LineBuilder().build(TokenMerger().merge(tokens)).joinToString("\n") { it.text },
        needsReview = fill.fields.values.any { it.state != UiState.GREEN }, captureQuality = fill.overallHint)
}
