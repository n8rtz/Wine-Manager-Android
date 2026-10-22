package com.yourapp.wine.ocr

import android.graphics.Bitmap

/** Pixel-space declaration: only a geometrically rectified label may be sent to optional line-crop engines. */
enum class OcrInputKind { RECTIFIED_LABEL, WORKING_LOCALIZATION, FULL_FRAME_FALLBACK }

/** Implementations must return/cancel without borrowing the bitmap after the suspending call ends. */
interface OcrEngine : AutoCloseable {
    val id: String
    val available: Boolean
    /** Heavy native OCR runs on the base only, avoiding repeated correlated passes and a third bitmap. */
    fun supportsVariant(variantId: String): Boolean = true
    /** Emits completed lines before cancellation; the return list is not emitted a second time. */
    suspend fun recognizeStreaming(bitmap: Bitmap, variantId: String, deadline: ScanDeadline,
        emit: (List<RawToken>) -> Unit): List<RawToken> = recognize(bitmap, variantId, deadline).also(emit)
    /** No coordinates: releases an unused/failed-init client; never close an engine while native work is in flight. */
    override fun close() = Unit
    /** No coordinates: diagnostic type/code only; never return exception messages, file paths or input URIs. */
    fun failureDetail(error: Exception): String = "failed: ${error.javaClass.name}; cause=${error.cause?.javaClass?.name ?: "none"}"
    /** Returns OCR lines in supplied variant pixels; the ensemble maps to base label pixels and shares the elapsed-realtime deadline. */
    suspend fun recognize(bitmap: Bitmap, variantId: String, deadline: ScanDeadline): List<RawToken>
}

/** Explicit optional-asset/native-bridge hook, not a decoder for nonexistent model weights. */
class NotAvailable(override val id: String, val reason: String) : OcrEngine {
    override val available = false
    /** Receives rectified-label pixels but deliberately performs no inference. */
    override suspend fun recognize(bitmap: Bitmap, variantId: String, deadline: ScanDeadline): List<RawToken> = emptyList()
}
