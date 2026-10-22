// HOST TEST VALUE OBJECT ONLY. No pixels, OCR, native memory or Android Bitmap implementation.
package android.graphics
class Bitmap private constructor(val width: Int, val height: Int) {
    enum class Config { ARGB_8888 }
    var isRecycled = false
        private set
    fun recycle() { isRecycled = true }
    companion object { fun createBitmap(w: Int, h: Int, config: Config): Bitmap = Bitmap(w, h) }
}
