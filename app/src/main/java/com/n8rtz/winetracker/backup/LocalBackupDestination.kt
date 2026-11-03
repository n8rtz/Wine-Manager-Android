package com.n8rtz.winetracker.backup

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.MessageDigest

/**
 * Local copy in the public Documents/WineManager/ folder through MediaStore. No permission or
 * folder picker is needed for files this install creates itself. This is explicitly NOT off-device
 * protection: it survives an app-data wipe but not the loss of the phone.
 *
 * MediaStore.Downloads only accepts "Download/" as its primary directory, so Documents/ goes
 * through MediaStore.Files.getContentUri("external") (the same insert/query/delete collection).
 */
class LocalBackupDestination(private val context: Context) : BackupDestination {

    override val id: BackupDestinationId = BackupDestinationId.LOCAL

    override fun isConfigured(): Boolean = true

    override suspend fun upload(snapshot: BackupSnapshot): String = withContext(Dispatchers.IO) {
        val relativePath = relativePath()
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, snapshot.fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, MIME_TYPE)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(collectionUri(), values)
            ?: throw IOException("Could not create a local backup file in $relativePath.")
        try {
            resolver.openOutputStream(uri)?.use { output -> output.write(snapshot.bytes) }
                ?: throw IOException("Could not open the local backup file for writing.")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (error: Exception) {
            // A half-written pending file must not be offered as a restore candidate. Keep the
            // original failure as the cause even if this best-effort cleanup also fails.
            runCatching { resolver.delete(uri, null, null) }
                .exceptionOrNull()
                ?.let { cleanupError ->
                    Log.w("WineTracker", "LocalBackupDestination: pending-file cleanup failed", cleanupError)
                }
            throw error
        }
        Log.d("WineTracker", "LocalBackupDestination: wrote ${snapshot.fileName} to $relativePath")
        snapshot.fileName
    }

    override suspend fun verify(name: String, expectedSha256: String): Boolean = withContext(Dispatchers.IO) {
        val uri = findUri(name) ?: return@withContext false
        val actual = context.contentResolver.openInputStream(uri)?.use { input ->
            sha256Hex(input.readBytes())
        } ?: return@withContext false
        val matches = actual.equals(expectedSha256, ignoreCase = true)
        if (!matches) {
            Log.w("WineTracker", "LocalBackupDestination: read-back checksum mismatch for $name")
        }
        matches
    }

    override suspend fun list(): List<String> = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val projection = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME)
        val selection =
            "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        val selectionArgs = arrayOf(
            "${BackupSnapshots.PREFIX}%${BackupSnapshots.SUFFIX}",
            "${relativePath()}%"
        )
        val names = mutableListOf<String>()
        resolver.query(
            collectionUri(),
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameColumn) ?: continue
                if (BackupSnapshots.isAppBackup(name)) names.add(name)
            }
        }
        Log.d("WineTracker", "LocalBackupDestination: found ${names.size} local backup(s)")
        names
    }

    override suspend fun download(name: String): Uri = withContext(Dispatchers.IO) {
        findUri(name) ?: throw IOException("Local backup $name is no longer available.")
    }

    override suspend fun delete(name: String): Unit = withContext(Dispatchers.IO) {
        val uri = findUri(name) ?: throw IOException("Local backup $name is no longer available.")
        val removed = context.contentResolver.delete(uri, null, null)
        if (removed <= 0) throw IOException("Could not remove local backup $name.")
        Log.d("WineTracker", "LocalBackupDestination: pruned $name")
    }

    /** Only app-owned names in our folder are ever addressed, including for deletion. */
    private fun findUri(name: String): Uri? {
        if (!BackupSnapshots.isAppBackup(name)) {
            Log.w("WineTracker", "LocalBackupDestination: refusing a file that is not an app backup: $name")
            return null
        }
        val resolver = context.contentResolver
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection =
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        val selectionArgs = arrayOf(name, "${relativePath()}%")
        resolver.query(
            collectionUri(),
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            if (cursor.moveToFirst()) {
                return ContentUris.withAppendedId(
                    collectionUri(),
                    cursor.getLong(idColumn)
                )
            }
        }
        return null
    }

    private fun relativePath(): String = "${Environment.DIRECTORY_DOCUMENTS}/$FOLDER_NAME"

    /** MediaStore.Files is the collection that accepts Documents/ as a primary directory. */
    private fun collectionUri(): Uri = MediaStore.Files.getContentUri("external")

    private companion object {
        const val FOLDER_NAME = "WineManager"
        const val MIME_TYPE = "application/json"
    }
}

internal fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
