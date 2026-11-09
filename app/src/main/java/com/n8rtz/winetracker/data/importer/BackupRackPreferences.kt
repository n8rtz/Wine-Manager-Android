package com.n8rtz.winetracker.data.importer

import android.content.Context
import java.util.Locale

/** SharedPreferences cellar rack state included in portable JSON backups. */
object BackupRackPreferences {
    const val PREFERENCES_FILE = "cellar_racks_v1"
    const val RACK_NAMES_KEY = "rack_names"
    const val LEGACY_USER_RACKS_KEY = "user_racks"
    const val ASSIGNMENTS_KEY = "assignments"
    const val NEW_ADDITIONS_EXCLUSIONS_KEY = "new_additions_exclusions"
    private val builtInRackNames = setOf("Long-term aging", "Daily drinkers", "Special occasions")

    fun read(context: Context): RackPreferencesSnapshot {
        val prefs = context.applicationContext.getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE)
        val names = if (prefs.contains(RACK_NAMES_KEY)) {
            prefs.getStringSet(RACK_NAMES_KEY, emptySet()).orEmpty()
        } else {
            builtInRackNames + prefs.getStringSet(LEGACY_USER_RACKS_KEY, emptySet()).orEmpty()
        }
        val assignments = prefs.getStringSet(ASSIGNMENTS_KEY, emptySet()).orEmpty().mapNotNull { entry ->
            val parts = entry.split('|', limit = 2)
            if (parts.size != 2) null else parts[0].toLongOrNull()?.let { it to parts[1] }
        }.groupBy({ it.first }, { it.second }).map { (bottleId, racks) ->
            BackupRackAssignment(bottleId, racks.distinct().sortedBy { it.lowercase(Locale.ROOT) })
        }.sortedBy { it.bottleId }
        val exclusions = prefs.getStringSet(NEW_ADDITIONS_EXCLUSIONS_KEY, emptySet()).orEmpty()
            .mapNotNull { it.toLongOrNull() }.distinct().sorted()
        return RackPreferencesSnapshot(
            rackNames = names.distinct().sortedBy { it.lowercase(Locale.ROOT) },
            assignments = assignments,
            newAdditionsExclusions = exclusions
        )
    }

    fun write(context: Context, rackNames: List<String>, assignments: List<BackupRackAssignment>, exclusions: List<Long>) {
        val prefs = context.applicationContext.getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE)
        val customNames = rackNames.filterNot { name -> builtInRackNames.any { it.equals(name, ignoreCase = true) } }
        prefs.edit()
            .putStringSet(RACK_NAMES_KEY, rackNames.toSet())
            .putStringSet(LEGACY_USER_RACKS_KEY, customNames.toSet())
            .putStringSet(ASSIGNMENTS_KEY, assignments.flatMap { row ->
                row.rackNames.map { rack -> "${row.bottleId}|$rack" }
            }.toSet())
            .putStringSet(NEW_ADDITIONS_EXCLUSIONS_KEY, exclusions.map { it.toString() }.toSet())
            .apply()
    }
}

data class RackPreferencesSnapshot(
    val rackNames: List<String>,
    val assignments: List<BackupRackAssignment>,
    val newAdditionsExclusions: List<Long>
)
