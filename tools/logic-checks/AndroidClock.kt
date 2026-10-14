// HOST TEST STAND-IN ONLY. No Android runtime/elapsedRealtime implementation is claimed.
package android.os
object SystemClock { fun elapsedRealtime(): Long = System.nanoTime() / 1_000_000 }
