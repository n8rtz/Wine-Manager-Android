package com.n8rtz.winetracker.backup

import android.net.Uri

/** One produced snapshot, ready to be handed to a destination. */
data class BackupSnapshot(
    val fileName: String,
    val bytes: ByteArray,
    val sha256: String
)

/**
 * Contract every backup destination implements. The pipeline and the Backup & Restore screen only
 * know this interface, so adding Google Drive, GitHub or WebDAV does not change the scheduler.
 */
interface BackupDestination {
    val id: BackupDestinationId

    fun isConfigured(): Boolean

    /**
     * Checks the saved setup (credentials, folder grant, reachability) and returns a short status.
     * Throws with an actionable message when the destination cannot be used.
     */
    suspend fun testConnection(): String

    /** Writes [snapshot] and returns the name it was stored under. */
    suspend fun upload(snapshot: BackupSnapshot): String

    /** Reads the stored copy back and confirms it matches [expectedSha256]. */
    suspend fun verify(name: String, expectedSha256: String): Boolean

    suspend fun list(): List<String>

    /** Returns a content Uri the existing validated importer can open. */
    suspend fun download(name: String): Uri

    suspend fun delete(name: String)
}
