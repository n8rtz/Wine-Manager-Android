package com.n8rtz.winetracker.scanner

import android.content.Context
import java.util.Locale

/**
 * Developer-option toggle for the label-recognition timer shown in the
 * Add Wine scanner (live while a scan runs, final total afterwards).
 * Off by default; stored in its own small preferences file.
 */
object ScanTimerSettings {
    private const val PREFS_FILE = "wine_dev_options"
    private const val KEY_SHOW_RECOGNITION_TIMER = "show_recognition_timer"

    fun isEnabled(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            .getBoolean(KEY_SHOW_RECOGNITION_TIMER, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SHOW_RECOGNITION_TIMER, enabled)
            .apply()
    }

    /** "842 ms" under a second, otherwise seconds with one decimal ("7.3 s"). */
    fun formatDuration(elapsedMs: Long): String {
        val ms = elapsedMs.coerceAtLeast(0)
        return if (ms < 1_000) "$ms ms"
        else String.format(Locale.US, "%.1f s", ms / 1_000.0)
    }
}
