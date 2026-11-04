package com.n8rtz.winetracker.data.importer

import android.content.Context
import java.util.Locale

/**
 * W-057 Phase 2: the two app preferences that are not in Room but must survive a restore.
 *
 * The file/key literals mirror `TastingNoteViewModel` and `WineImageImportViewModel`
 * (`tools/tests/test_backup_preferences.py` asserts they stay in sync). Only these values are
 * written; no credentials, tokens or image bytes are ever included in a backup.
 */
object BackupPreferences {
    const val REVIEWER_PREFERENCES_FILE = "wine_manager_settings"
    const val REVIEWER_NAMES_KEY = "wine_reviewer_names"
    const val IMAGE_SETTINGS_FILE = "wine_image_import_settings"
    const val SOFT_BUDGET_KEY = "soft_budget_bytes"
    const val DEFAULT_SOFT_BUDGET_BYTES = 256L * 1024L * 1024L
    const val MIN_BUDGET_BYTES = 64L * 1024L * 1024L
    const val MAX_BUDGET_BYTES = 1_024L * 1024L * 1024L
    val DEFAULT_REVIEWER_NAMES: List<String> = listOf(
        "Costco Wine Blog",
        "Decanter",
        "James Suckling",
        "Konstantin Baum",
        "Peter Koff",
        "Wine Advocate",
        "Wine Enthusiast",
        "Wine Spectator"
    )

    /**
     * Mirrors `TastingNoteViewModel.loadReviewerNames()`: when the key has never been written the
     * app shows the built-in defaults, so a backup must record those defaults too. Otherwise a
     * restore from a fresh-install backup would silently clear the reviewer list.
     */
    fun readReviewers(context: Context): List<String> {
        val stored = context.applicationContext
            .getSharedPreferences(REVIEWER_PREFERENCES_FILE, Context.MODE_PRIVATE)
            .getStringSet(REVIEWER_NAMES_KEY, null) ?: return DEFAULT_REVIEWER_NAMES
        return normalizeReviewers(stored)
    }

    fun readBudgetBytes(context: Context): Long {
        val value = context.applicationContext
            .getSharedPreferences(IMAGE_SETTINGS_FILE, Context.MODE_PRIVATE)
            .getLong(SOFT_BUDGET_KEY, DEFAULT_SOFT_BUDGET_BYTES)
        return value.coerceIn(MIN_BUDGET_BYTES, MAX_BUDGET_BYTES)
    }

    fun writeReviewers(context: Context, names: List<String>) {
        context.applicationContext
            .getSharedPreferences(REVIEWER_PREFERENCES_FILE, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(REVIEWER_NAMES_KEY, normalizeReviewers(names).toSet())
            .apply()
    }

    fun writeBudgetBytes(context: Context, bytes: Long) {
        require(bytes in MIN_BUDGET_BYTES..MAX_BUDGET_BYTES) {
            "Offline image storage budget must be between $MIN_BUDGET_BYTES and $MAX_BUDGET_BYTES bytes."
        }
        context.applicationContext
            .getSharedPreferences(IMAGE_SETTINGS_FILE, Context.MODE_PRIVATE)
            .edit()
            .putLong(SOFT_BUDGET_KEY, bytes)
            .apply()
    }

    private fun normalizeReviewers(names: Iterable<String>): List<String> = names
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinctBy { it.lowercase(Locale.ROOT) }
        .sortedBy { it.lowercase(Locale.ROOT) }
}
