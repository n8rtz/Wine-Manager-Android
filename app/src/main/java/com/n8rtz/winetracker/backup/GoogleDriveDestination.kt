package com.n8rtz.winetracker.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Google Drive (or any other document provider) through the Android folder picker.
 *
 * The user picks a folder once; the read/write grant is made persistable and its tree Uri is kept
 * in the credential store. No Google Cloud OAuth client, Play services SDK or network permission
 * beyond the app's existing INTERNET is needed. A drive-side copy is confirmed by reading it back.
 */
class GoogleDriveDestination(
    private val context: Context,
    private val credentials: BackupCredentialStore
) : BackupDestination {

    override val id: BackupDestinationId = BackupDestinationId.GOOGLE_DRIVE

    private fun treeUri(): Uri {
        val value = credentials.driveTreeUri()
        if (value.isBlank()) {
            throw IOException("Google Drive setup is incomplete: choose a Drive folder first.")
        }
        return Uri.parse(value)
    }

    override fun isConfigured(): Boolean {
        val value = credentials.driveTreeUri()
        if (value.isBlank()) return false
        val uri = Uri.parse(value)
        return context.contentResolver.persistedUriPermissions.any { permission ->
            permission.uri == uri && permission.isReadPermission && permission.isWritePermission
        }
    }

    private fun childrenUri(tree: Uri): Uri =
        DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

    private fun parentUri(tree: Uri): Uri =
        DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

    private fun findDocumentUri(name: String): Uri? {
        if (!BackupSnapshots.isAppBackup(name)) return null
        val tree = treeUri()
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME
        )
        context.contentResolver.query(childrenUri(tree), projection, null, null, null)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                if (cursor.getString(nameColumn) == name) {
                    val documentId = cursor.getString(idColumn) ?: continue
                    return DocumentsContract.buildDocumentUriUsingTree(tree, documentId)
                }
            }
        }
        return null
    }

    private fun displayNameOf(documentUri: Uri): String? {
        val projection = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        return context.contentResolver.query(documentUri, projection, null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            if (cursor.moveToFirst()) cursor.getString(column) else null
        }
    }

    override suspend fun testConnection(): String = withContext(Dispatchers.IO) {
        if (!isConfigured()) {
            throw IOException("The Drive folder grant is no longer valid. Choose the folder again.")
        }
        val tree = treeUri()
        val projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
        val cursor = context.contentResolver.query(childrenUri(tree), projection, null, null, null)
            ?: throw IOException("The chosen Drive folder could not be opened. Reconnect it.")
        cursor.use { }
        "Drive folder ready"
    }

    override suspend fun upload(snapshot: BackupSnapshot): String = withContext(Dispatchers.IO) {
        val tree = treeUri()
        val created = DocumentsContract.createDocument(
            context.contentResolver,
            parentUri(tree),
            MIME_TYPE,
            snapshot.fileName
        ) ?: throw IOException("The chosen Drive folder did not accept ${snapshot.fileName}.")
        context.contentResolver.openOutputStream(created, "w")?.use { output ->
            output.write(snapshot.bytes)
        } ?: throw IOException("Could not write ${snapshot.fileName} to the chosen Drive folder.")
        val actualName = displayNameOf(created) ?: snapshot.fileName
        Log.d("WineTracker", "GoogleDriveDestination: uploaded $actualName")
        actualName
    }

    override suspend fun verify(name: String, expectedSha256: String): Boolean = withContext(Dispatchers.IO) {
        val documentUri = findDocumentUri(name)
            ?: return@withContext false
        val actual = context.contentResolver.openInputStream(documentUri)?.use { input ->
            sha256Hex(input.readBytes())
        } ?: return@withContext false
        val matches = actual.equals(expectedSha256, ignoreCase = true)
        if (!matches) Log.w("WineTracker", "GoogleDriveDestination: checksum mismatch for $name")
        matches
    }

    override suspend fun list(): List<String> = withContext(Dispatchers.IO) {
        val tree = treeUri()
        val projection = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        val names = mutableListOf<String>()
        context.contentResolver.query(childrenUri(tree), projection, null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val name = cursor.getString(column) ?: continue
                if (BackupSnapshots.isAppBackup(name)) names.add(name)
            }
        }
        Log.d("WineTracker", "GoogleDriveDestination: found ${names.size} Drive backup(s)")
        names
    }

    override suspend fun download(name: String): Uri = withContext(Dispatchers.IO) {
        findDocumentUri(name) ?: throw IOException("Drive backup $name is no longer available.")
    }

    override suspend fun delete(name: String): Unit = withContext(Dispatchers.IO) {
        val documentUri = findDocumentUri(name)
            ?: throw IOException("Drive backup $name is no longer available.")
        if (!DocumentsContract.deleteDocument(context.contentResolver, documentUri)) {
            throw IOException("Could not remove Drive backup $name.")
        }
        Log.d("WineTracker", "GoogleDriveDestination: pruned $name")
    }

    companion object {
        private const val MIME_TYPE = "application/json"

        /** Called once after the folder picker returns; the grant must be persisted for app-open uploads. */
        fun persistTreeUri(context: Context, uri: Uri) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, flags)
            BackupCredentialStore(context).saveDriveTreeUri(uri.toString())
            Log.d("WineTracker", "GoogleDriveDestination: persisted Drive folder grant")
        }
    }
}
