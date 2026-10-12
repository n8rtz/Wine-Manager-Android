package com.n8rtz.winetracker.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

class PreparedWineImage internal constructor(
    internal val cacheFile: File,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val sha256: String
)

data class StoredWineImage(
    val relativePath: String,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val sha256: String
)

class WineImageStorageException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Downloads and normalizes only a user-selected Open Food Facts candidate. */
class WineImageFileStore(private val context: android.content.Context) {

    suspend fun prepare(candidate: WineImageCandidate, kind: WineImageKind): PreparedWineImage =
        withContext(Dispatchers.IO) {
            val sourceUrl = requireOpenFoodFactsUrl(candidate.sourceUrl)
            val stagingDirectory = File(context.cacheDir, STAGING_DIRECTORY)
            if (!stagingDirectory.exists() && !stagingDirectory.mkdirs()) {
                throw WineImageStorageException("Could not create temporary image storage.")
            }
            val downloaded = File.createTempFile("off-download-", ".image", stagingDirectory)
            var encoded: File? = null
            try {
                downloadBounded(sourceUrl, downloaded)
                val maxEdge = when (kind) {
                    WineImageKind.BOTTLE -> BOTTLE_MAX_EDGE
                    WineImageKind.LABEL -> LABEL_MAX_EDGE
                }
                val normalized = normalizeImage(downloaded, maxEdge)
                val encodedFile = File.createTempFile("off-prepared-", ".webp", stagingDirectory)
                encoded = encodedFile
                FileOutputStream(encodedFile).use { output ->
                    output.write(normalized.bytes)
                    output.flush()
                    output.fd.sync()
                }
                PreparedWineImage(
                    cacheFile = encodedFile,
                    width = normalized.width,
                    height = normalized.height,
                    sizeBytes = normalized.bytes.size.toLong(),
                    sha256 = sha256(normalized.bytes)
                ).also { encoded = null }
            } finally {
                downloaded.delete()
                encoded?.delete()
            }
        }

    suspend fun install(definitionId: Long, prepared: PreparedWineImage): StoredWineImage =
        withContext(Dispatchers.IO) {
            require(definitionId > 0L) { "A saved wine is required before its image can be imported." }
            if (prepared.sizeBytes <= 0L || prepared.sizeBytes > MAX_DERIVATIVE_BYTES) {
                throw WineImageStorageException("The compressed image exceeds the 500 KB per-image limit.")
            }
            if (context.filesDir.usableSpace < prepared.sizeBytes + FREE_SPACE_RESERVE_BYTES) {
                throw WineImageStorageException("Not enough device storage is available to keep this image offline.")
            }

            val imageDirectory = File(context.filesDir, IMAGE_DIRECTORY)
            if (!imageDirectory.exists() && !imageDirectory.mkdirs()) {
                throw WineImageStorageException("Could not create offline image storage.")
            }
            val fileName = "$definitionId-${prepared.sha256}.webp"
            val destination = File(imageDirectory, fileName)
            if (!destination.isFile) {
                val temporary = File(imageDirectory, ".$fileName-${UUID.randomUUID()}.tmp")
                try {
                    prepared.cacheFile.inputStream().buffered().use { input ->
                        FileOutputStream(temporary).use { output ->
                            val buffer = ByteArray(COPY_BUFFER_BYTES)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                            }
                            output.flush()
                            output.fd.sync()
                        }
                    }
                    if (!temporary.renameTo(destination)) {
                        throw WineImageStorageException("Could not finalize the compressed offline image.")
                    }
                } finally {
                    temporary.delete()
                }
            }
            prepared.cacheFile.delete()
            StoredWineImage(
                relativePath = "$IMAGE_DIRECTORY/$fileName",
                width = prepared.width,
                height = prepared.height,
                sizeBytes = destination.length(),
                sha256 = prepared.sha256
            )
        }

    fun discard(prepared: PreparedWineImage?) {
        prepared?.cacheFile?.delete()
    }

    suspend fun deleteLocalImage(relativePath: String): Boolean = withContext(Dispatchers.IO) {
        val file = localFile(context, relativePath) ?: return@withContext false
        !file.exists() || file.delete()
    }

    suspend fun hasLocalImage(relativePath: String): Boolean = withContext(Dispatchers.IO) {
        localFile(context, relativePath)?.isFile == true
    }

    suspend fun cleanupOrphanFiles(referencedPaths: Set<String>) = withContext(Dispatchers.IO) {
        File(context.filesDir, IMAGE_DIRECTORY).listFiles().orEmpty().forEach { file ->
            val relativePath = "$IMAGE_DIRECTORY/${file.name}"
            if (relativePath !in referencedPaths) file.delete()
        }
        File(context.cacheDir, STAGING_DIRECTORY).deleteRecursively()
    }

    suspend fun clearStoredImages() = withContext(Dispatchers.IO) {
        File(context.filesDir, IMAGE_DIRECTORY).deleteRecursively()
        File(context.cacheDir, STAGING_DIRECTORY).deleteRecursively()
    }

    companion object {
        const val IMAGE_DIRECTORY = "wine-images"
        const val MAX_DERIVATIVE_BYTES = 500L * 1024L
        const val DEFAULT_SOFT_BUDGET_BYTES = 256L * 1024L * 1024L
        const val TARGET_DERIVATIVE_BYTES = 300L * 1024L
        const val BOTTLE_MAX_EDGE = 1_024
        const val LABEL_MAX_EDGE = 1_200
        private const val STAGING_DIRECTORY = "wine-image-import"
        private const val MAX_SOURCE_BYTES = 12L * 1024L * 1024L
        private const val MAX_SOURCE_EDGE = 20_000
        private const val MAX_SOURCE_PIXELS = 60_000_000L
        private const val FREE_SPACE_RESERVE_BYTES = 10L * 1024L * 1024L
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 15_000
        private const val MAX_REDIRECTS = 4
        private const val COPY_BUFFER_BYTES = 16 * 1024
        private val ALLOWED_MIME_TYPES = setOf("image/jpeg", "image/png", "image/webp", "image/avif")
        private val WEBP_QUALITIES = listOf(82, 76, 70, 64, 58)
        private const val MAX_COMPRESSION_PASSES = 5
        private const val MIN_DETAIL_EDGE = 640
        private const val RESIZE_FACTOR = 0.86f

        /** Resolve only paths created by this feature; never interpret a backup path as absolute. */
        fun localFile(context: android.content.Context, relativePath: String?): File? {
            if (relativePath.isNullOrBlank() || !relativePath.startsWith("$IMAGE_DIRECTORY/")) return null
            if (relativePath.contains('\\')) return null
            val segments = relativePath.split('/')
            if (segments.firstOrNull() != IMAGE_DIRECTORY || segments.drop(1).any { it.isBlank() || it == "." || it == ".." }) {
                return null
            }
            return File(context.filesDir, relativePath)
        }

        private fun requireOpenFoodFactsUrl(raw: String): String {
            val uri = runCatching { URI(raw) }.getOrNull()
                ?: throw WineImageStorageException("The selected image URL is invalid.")
            val host = uri.host?.lowercase(Locale.ROOT)
            if (!uri.scheme.equals("https", ignoreCase = true) ||
                host == null ||
                (host != "openfoodfacts.org" && !host.endsWith(".openfoodfacts.org")) ||
                uri.userInfo != null || (uri.port != -1 && uri.port != 443)
            ) {
                throw WineImageStorageException("Only HTTPS images hosted by Open Food Facts can be imported.")
            }
            return uri.toASCIIString()
        }
    }

    private suspend fun downloadBounded(sourceUrl: String, destination: File) {
        var currentUrl = requireOpenFoodFactsUrl(sourceUrl)
        for (redirectCount in 0..MAX_REDIRECTS) {
            val connection = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("Accept", "image/avif,image/webp,image/png,image/jpeg")
                setRequestProperty("User-Agent", "WineManagerAndroid/1.0")
            }
            try {
                val responseCode = connection.responseCode
                if (responseCode in 300..399) {
                    if (redirectCount == MAX_REDIRECTS) {
                        throw WineImageStorageException("Open Food Facts redirected the image request too many times.")
                    }
                    val location = connection.getHeaderField("Location")
                        ?: throw WineImageStorageException("Open Food Facts returned an invalid image redirect.")
                    val redirectUrl = runCatching { URI(currentUrl).resolve(location).toString() }.getOrNull()
                        ?: throw WineImageStorageException("Open Food Facts returned an invalid image redirect.")
                    // Validate each hop before issuing another request; never follow a redirect off-domain.
                    currentUrl = requireOpenFoodFactsUrl(redirectUrl)
                    continue
                }
                if (responseCode !in 200..299) {
                    throw WineImageStorageException("Open Food Facts image download failed (HTTP $responseCode).")
                }
                val mimeType = connection.contentType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
                if (mimeType !in ALLOWED_MIME_TYPES) {
                    throw WineImageStorageException("The selected candidate is not a supported still image.")
                }
                if (connection.contentLengthLong > MAX_SOURCE_BYTES) {
                    throw WineImageStorageException("The source image is larger than the 12 MB download limit.")
                }

                connection.inputStream.use { rawInput ->
                    BufferedInputStream(rawInput).use { input ->
                        FileOutputStream(destination).use { output ->
                            val buffer = ByteArray(COPY_BUFFER_BYTES)
                            var total = 0L
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                if (total > MAX_SOURCE_BYTES) {
                                    throw WineImageStorageException("The source image is larger than the 12 MB download limit.")
                                }
                                output.write(buffer, 0, count)
                            }
                            output.flush()
                            output.fd.sync()
                        }
                    }
                }
                return
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun normalizeImage(source: File, maxEdge: Int): EncodedImage {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0 || width > MAX_SOURCE_EDGE || height > MAX_SOURCE_EDGE ||
            width.toLong() * height.toLong() > MAX_SOURCE_PIXELS
        ) {
            throw WineImageStorageException("The source image has invalid or unsupported dimensions.")
        }

        var sampleSize = 1
        while (maxOf(width, height) / (sampleSize * 2) >= maxEdge) sampleSize *= 2
        var bitmap = BitmapFactory.decodeFile(
            source.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        ) ?: throw WineImageStorageException("Open Food Facts returned an image that could not be decoded.")

        try {
            bitmap = applyExifOrientation(source, bitmap)
            if (maxOf(bitmap.width, bitmap.height) > maxEdge) {
                val scale = maxEdge.toFloat() / maxOf(bitmap.width, bitmap.height)
                val scaled = Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt().coerceAtLeast(1),
                    (bitmap.height * scale).toInt().coerceAtLeast(1),
                    true
                )
                if (scaled !== bitmap) bitmap.recycle()
                bitmap = scaled
            }

            var bestBytes: ByteArray? = null
            var bestWidth = bitmap.width
            var bestHeight = bitmap.height
            repeat(MAX_COMPRESSION_PASSES) { pass ->
                for (quality in WEBP_QUALITIES) {
                    val stream = ByteArrayOutputStream()
                    if (!bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, quality, stream)) {
                        throw WineImageStorageException("Could not compress the selected image.")
                    }
                    val bytes = stream.toByteArray()
                    if (bestBytes == null || bytes.size < bestBytes!!.size) {
                        bestBytes = bytes
                        bestWidth = bitmap.width
                        bestHeight = bitmap.height
                    }
                    if (bytes.size <= TARGET_DERIVATIVE_BYTES) {
                        return EncodedImage(bytes, bitmap.width, bitmap.height)
                    }
                }

                if (pass < MAX_COMPRESSION_PASSES - 1 && minOf(bitmap.width, bitmap.height) > MIN_DETAIL_EDGE) {
                    val scale = RESIZE_FACTOR
                    val smaller = Bitmap.createScaledBitmap(
                        bitmap,
                        (bitmap.width * scale).toInt().coerceAtLeast(1),
                        (bitmap.height * scale).toInt().coerceAtLeast(1),
                        true
                    )
                    if (smaller !== bitmap) bitmap.recycle()
                    bitmap = smaller
                }
            }

            val accepted = bestBytes
                ?: throw WineImageStorageException("Could not produce a compressed image.")
            if (accepted.size > MAX_DERIVATIVE_BYTES) {
                throw WineImageStorageException("This image cannot be compressed below the 500 KB per-image limit. Choose another candidate.")
            }
            return EncodedImage(accepted, bestWidth, bestHeight)
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun applyExifOrientation(source: File, bitmap: Bitmap): Bitmap {
        val orientation = runCatching {
            ExifInterface(source.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return bitmap
        }
        val oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (oriented !== bitmap) bitmap.recycle()
        return oriented
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private data class EncodedImage(val bytes: ByteArray, val width: Int, val height: Int)
}
