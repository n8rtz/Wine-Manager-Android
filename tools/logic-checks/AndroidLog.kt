// HOST TEST STAND-IN ONLY. Never included in application source sets.
package android.util
object Log { fun d(tag: String, text: String): Int = 0; fun w(tag: String, text: String, error: Throwable): Int = 0 }
