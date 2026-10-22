// HOST TEST STAND-INS ONLY. Not Android implementations and never compiled into the app.
package android.graphics

data class PointF(var x: Float = 0f, var y: Float = 0f)
class RectF(var left: Float = 0f, var top: Float = 0f, var right: Float = 0f, var bottom: Float = 0f) {
    constructor(other: RectF) : this(other.left, other.top, other.right, other.bottom)
    fun width() = right - left
    fun height() = bottom - top
    fun centerX() = (left + right) / 2f
    fun centerY() = (top + bottom) / 2f
}
