package com.n8rtz.winetracker.backup

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Stages a downloaded remote snapshot in the cache so the existing validated importer can open it
 * through the app's FileProvider (see res/xml/file_paths.xml). Cache files are not backed up.
 */
internal fun downloadedBackupUri(context: Context, fileName: String, bytes: ByteArray): Uri {
    require(BackupSnapshots.isAppBackup(fileName)) {
        "Refusing to stage a file that is not an app backup: $fileName"
    }
    val directory = File(context.cacheDir, "backup-downloads").apply { mkdirs() }
    val file = File(directory, fileName)
    file.writeBytes(bytes)
    return FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
}
