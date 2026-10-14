package com.yourapp.wine.ocr

import android.graphics.Bitmap

/** Implementations must return/cancel without borrowing the bitmap after the suspending call ends. */
interface OcrEngine : AutoCloseable {
    val id: String
    val available: Boolean
    /** No coordinates: releases an unused/failed-init client; never close an engine while native work is in flight. */
    override fun close() = Unit
    /** Returns one token per OCR line in rectified-label pixels; implementations must honor the shared elapsed-realtime deadline. */
    suspend fun recognize(bitmap: Bitmap, variantId: String, deadline: ScanDeadline): List<RawToken>
}

/** Explicit optional-asset/native-bridge hook, not a decoder for nonexistent model weights. */
class NotAvailable(override val id: String, val reason: String) : OcrEngine {
    override val available = false
    /** Receives rectified-label pixels but deliberately performs no inference. */
    override suspend fun recognize(bitmap: Bitmap, variantId: String, deadline: ScanDeadline): List<RawToken> = emptyList()
}
