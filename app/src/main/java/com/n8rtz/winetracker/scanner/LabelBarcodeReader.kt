package com.n8rtz.winetracker.scanner

import android.util.Log
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

/**
 * Reads the EAN/UPC barcode a bottle carries.
 *
 * A barcode is the one piece of a wine label that can be identified exactly instead of guessed, so
 * the scanner tries it on the same still image the text recognizer gets. Only the retail GTIN
 * formats are enabled, and every read must pass its GS1 check digit.
 */
internal class LabelBarcodeReader {
    private val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                Barcode.FORMAT_EAN_13,
                Barcode.FORMAT_EAN_8,
                Barcode.FORMAT_UPC_A,
                Barcode.FORMAT_UPC_E
            )
            .build()
    )

    suspend fun read(image: InputImage): String? = try {
        scanner.process(image).await()
            .mapNotNull { barcode -> Gtin.normalize(barcode.rawValue) }
            .firstOrNull(Gtin::isValid)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Log.w(TAG, "Barcode scanning failed; continuing with text recognition only", error)
        null
    }

    fun close() {
        scanner.close()
    }

    private companion object {
        const val TAG = "LabelBarcodeReader"
    }
}
