package com.n8rtz.winetracker.image

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Local files for already-approved wine images. Display resolves paths here and a JSON restore
 * clears them; the offline image import/review flow that created them has been removed.
 */
class WineImageFileStore(private val context: Context) {

    suspend fun clearStoredImages() = withContext(Dispatchers.IO) {
        File(context.filesDir, IMAGE_DIRECTORY).deleteRecursively()
        File(context.cacheDir, STAGING_DIRECTORY).deleteRecursively()
    }

    companion object {
        const val IMAGE_DIRECTORY = "wine-images"
        private const val STAGING_DIRECTORY = "wine-image-import"

        /** Resolve only paths created by this feature; never interpret a backup path as absolute. */
        fun localFile(context: Context, relativePath: String?): File? {
            if (relativePath.isNullOrBlank() || !relativePath.startsWith("$IMAGE_DIRECTORY/")) return null
            if (relativePath.contains('\\')) return null
            val segments = relativePath.split('/')
            if (segments.firstOrNull() != IMAGE_DIRECTORY || segments.drop(1).any { it.isBlank() || it == "." || it == ".." }) {
                return null
            }
            return File(context.filesDir, relativePath)
        }
    }
}
