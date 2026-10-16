package com.n8rtz.winetracker.data.importer

import android.content.Context
import android.util.Log
import com.n8rtz.winetracker.BuildConfig
import com.n8rtz.winetracker.data.local.WineDatabase
import java.io.FileNotFoundException
import java.security.MessageDigest

/**
 * DEBUG BUILDS ONLY. Restores a developer's own JSON backup after a fresh install so rapid on-device testing does not
 * start from an empty database every time Android removes and reinstalls the app.
 *
 * The backup is bundled as `assets/dev-seed/WineTracker_Backup.json` from `app/src/debug/assets/dev-seed/`, which only
 * exists in the debug source set, so it is never packaged into a release build. It holds the developer's real cellar
 * (tracked in this private repo like `_backup/`); do not share debug APKs.
 *
 * Safety rules (agent rule 15 — no silent behaviour):
 * - Does nothing unless this is a debug build and the seed file exists.
 * - Only restores when the database is completely empty; it never replaces existing data.
 * - Remembers (per seed content hash) that it has restored, so "Reset Database" does not bring the seed back.
 *   The flag lives in app preferences, so a reinstall or "Clear storage" restores again.
 * - Every outcome is logged, and the caller shows it to the developer.
 */
class DevSeedRestorer(
    private val context: Context,
    private val database: WineDatabase
) {
    sealed class Result {
        object NotApplicable : Result()
        object NoSeedFile : Result()
        object AlreadyApplied : Result()
        object DatabaseNotEmpty : Result()
        data class Restored(val definitions: Int, val notes: Int) : Result()
        data class Failed(val message: String) : Result()
    }

    suspend fun restoreIfNeeded(): Result {
        if (!BuildConfig.DEBUG) return Result.NotApplicable

        val bytes = try {
            context.assets.open(SEED_ASSET).use { it.readBytes() }
        } catch (_: FileNotFoundException) {
            Log.d(TAG, "No dev seed bundled ($SEED_ASSET); starting with whatever data is on the device")
            return Result.NoSeedFile
        } catch (e: Exception) {
            Log.w(TAG, "Could not read the dev seed", e)
            return Result.Failed(e.message ?: "Could not read the dev seed")
        }

        val seedHash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val preferences = context.getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE)
        if (preferences.getString(KEY_APPLIED_HASH, null) == seedHash) {
            Log.d(TAG, "Dev seed already applied on this install; leaving the database alone")
            return Result.AlreadyApplied
        }

        return try {
            val isEmpty = database.wineDao().getAllDefinitionsOnce().isEmpty() &&
                database.inventoryDao().getAllBottlesOnce().isEmpty() &&
                database.tastingNoteDao().getAllNotesOnce().isEmpty() &&
                database.consumptionEventDao().getAllEventsOnce().isEmpty()
            if (!isEmpty) {
                Log.i(TAG, "Database already has data; the dev seed is not applied")
                return Result.DatabaseNotEmpty
            }

            DataImporter(context, database).importFromStream(bytes.inputStream())
            preferences.edit().putString(KEY_APPLIED_HASH, seedHash).apply()
            val definitions = database.wineDao().getAllDefinitionsOnce().size
            val notes = database.tastingNoteDao().getAllNotesOnce().size
            Log.i(TAG, "Dev seed restored: $definitions wines, $notes notes")
            Result.Restored(definitions, notes)
        } catch (e: Exception) {
            Log.w(TAG, "Dev seed restore failed; the database was left as it was", e)
            Result.Failed(e.message ?: "Restore failed")
        }
    }

    private companion object {
        const val TAG = "DevSeedRestorer"
        const val SEED_ASSET = "dev-seed/WineTracker_Backup.json"
        const val PREFERENCES_FILE = "dev_seed"
        const val KEY_APPLIED_HASH = "applied_seed_hash"
    }
}
