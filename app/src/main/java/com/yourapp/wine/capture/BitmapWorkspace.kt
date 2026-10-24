package com.yourapp.wine.capture

import android.graphics.Bitmap

/** Per-scan ownership: every decoded/warped/variant bitmap is released on its allocating worker path. */
class BitmapWorkspace : AutoCloseable {
    private val owned = linkedSetOf<Bitmap>()
    var peakCount: Int = 0
        private set
    val aliveCount: Int get() = owned.size

    /** Owns a working/rectified bitmap with long edge <=1920; never allows more than two alive. */
    fun own(bitmap: Bitmap): Bitmap {
        if (bitmap in owned) return bitmap
        if (owned.size >= 2 || maxOf(bitmap.width, bitmap.height) > 1920) {
            bitmap.recycle()
            error("Working bitmap budget exceeded")
        }
        owned += bitmap
        peakCount = maxOf(peakCount, owned.size)
        return bitmap
    }

    /** Allocates in the caller's current coordinate space; it must be bounded working or rectified pixels. */
    fun create(width: Int, height: Int): Bitmap {
        require(owned.size < 2 && width > 0 && height > 0 && maxOf(width, height) <= 1920)
        return own(Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888))
    }

    /** Releases an owned working/rectified bitmap immediately, not after the next capture. */
    fun release(bitmap: Bitmap) { if (owned.remove(bitmap) && !bitmap.isRecycled) bitmap.recycle() }

    /** No coordinate conversion: called in finally on the same Default worker path that allocated the bitmaps. */
    override fun close() { owned.toList().forEach(::release) }
}
