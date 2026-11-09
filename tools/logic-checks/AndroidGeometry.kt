// HOST TEST STAND-INS ONLY. Not Android implementations and never compiled into the app.
package android.graphics

data class PointF(var x: Float = 0f, var y: Float = 0f)
class RectF(var left: Float = 0f, var top: Float = 0f, var right: Float = 0f, var bottom: Float = 0f) {
    constructor(other: RectF) : this(other.left, other.top, other.right, other.bottom)
    fun width() = right - left
    fun height() = bottom - top
    fun centerX() = (left + right) / 2f
    fun centerY() = (top + bottom) / 2f
    fun isEmpty() = left >= right || top >= bottom
    // Mirrors android.graphics.RectF.union(RectF): an empty receiver adopts the other rectangle.
    fun union(other: RectF) {
        if (isEmpty()) { set(other); return }
        if (other.isEmpty()) return
        if (other.left < left) left = other.left
        if (other.top < top) top = other.top
        if (other.right > right) right = other.right
        if (other.bottom > bottom) bottom = other.bottom
    }
    fun set(other: RectF) {
        left = other.left; top = other.top; right = other.right; bottom = other.bottom
    }
}
