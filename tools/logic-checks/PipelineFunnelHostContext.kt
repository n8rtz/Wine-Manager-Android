package android.content

import java.io.File

/** Host asset reader only; this is not an Android runtime or device test. */
class Context(root: File) {
    val assets = Assets(root)
    class Assets(private val root: File) {
        fun open(name: String) = File(root, name).inputStream()
    }
}
