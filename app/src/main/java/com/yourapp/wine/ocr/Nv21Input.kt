package com.yourapp.wine.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** A byte-owned, grayscale NV21 input decouples ML Kit's uncancellable Task from recyclable Bitmaps. */
internal class Nv21Input private constructor(private var bytes: ByteArray?, private var input: InputImage?) {
    val image: InputImage get() = requireNotNull(input)
    fun release() { input = null; bytes = null }

    companion object {
        /** Copies rectified pixels; even-size edge padding does not shift or scale any OCR polygon. */
        suspend fun copy(bitmap: Bitmap, deadline: ScanDeadline): Nv21Input? {
            val width = (bitmap.width + 1) and -2
            val height = (bitmap.height + 1) and -2
            val bytes = ByteArray(width * height * 3 / 2)
            val row = IntArray(bitmap.width)
            for (y in 0 until height) {
                if (y % 32 == 0) { coroutineContext.ensureActive(); if (deadline.expired) return null }
                bitmap.getPixels(row, 0, bitmap.width, 0, y.coerceAtMost(bitmap.height - 1), bitmap.width, 1)
                for (x in 0 until width) {
                    val pixel = row[x.coerceAtMost(bitmap.width - 1)]
                    val r = (pixel shr 16) and 255; val g = (pixel shr 8) and 255; val b = pixel and 255
                    bytes[y * width + x] = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).toByte()
                }
            }
            bytes.fill(128.toByte(), width * height, bytes.size)
            return Nv21Input(bytes, InputImage.fromByteArray(bytes, width, height, 0, InputImage.IMAGE_FORMAT_NV21))
        }
    }
}
