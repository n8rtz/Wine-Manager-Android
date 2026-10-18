package com.yourapp.wine.capture

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicReference

/** Original compressed image bytes only; the scanner decodes/EXIF-orients later under its two-Bitmap limit. */
data class LocalLabelPhoto(val file: File, val bytes: Long)
class LabelPhotoTooLargeException : IOException("Selected label photo exceeds the 32 MiB import limit")

/** Copies the granted picker URI, not the user's original file; no persistent storage permission is requested. */
class SelectedLabelPhotoImporter(context: Context) {
    private val app = context.applicationContext

    /** Selected photo's original bytes in -> private temporary copy out; cancellation/error removes only that copy. */
    suspend fun copy(uri: Uri): LocalLabelPhoto = coroutineScope {
        require(uri.scheme == "content") { "Select an image using the system photo picker" }
        val owner = currentCoroutineContext()
        val cancellation = CancellationSignal()
        val activeStream = AtomicReference<InputStream?>()
        var output: File? = null
        val watcher = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally {
                if (!owner.isActive) {
                    runCatching { cancellation.cancel() }
                    runCatching { activeStream.getAndSet(null)?.close() }
                }
            }
        }
        try {
            withContext(Dispatchers.IO) {
                runInterruptible {
                    app.contentResolver.openAssetFileDescriptor(uri, "r", cancellation)?.use { descriptor ->
                        owner.ensureActive()
                        if (descriptor.declaredLength > MAX_INPUT_BYTES) throw LabelPhotoTooLargeException()
                        val target = File.createTempFile("wine-selected-label-", ".img", app.cacheDir)
                        output = target
                        descriptor.createInputStream().use { input ->
                            activeStream.set(input)
                            owner.ensureActive()
                            target.outputStream().use { destination ->
                                val bytes = copyLabelBytes(input, destination) { owner.ensureActive() }
                                if (bytes == 0L) throw IOException("Selected photo is empty")
                                LocalLabelPhoto(target, bytes)
                            }
                        }
                    } ?: throw IOException("Selected photo cannot be opened")
                }
            }
        } catch (error: Throwable) {
            output?.delete()
            throw error
        } finally {
            activeStream.set(null)
            watcher.cancel()
        }
    }

    companion object { const val MAX_INPUT_BYTES = 32L * 1024 * 1024 }
}

/** No coordinates: bounded streaming transfer, never a full-size byte-array or Bitmap copy. */
internal fun copyLabelBytes(input: InputStream, output: OutputStream,
                            maxBytes: Long = SelectedLabelPhotoImporter.MAX_INPUT_BYTES,
                            checkActive: () -> Unit = {}): Long {
    require(maxBytes > 0)
    val buffer = ByteArray(32 * 1024)
    var bytes = 0L
    while (true) {
        checkActive()
        val read = input.read(buffer)
        if (read < 0) return bytes
        if (read == 0) continue
        bytes += read
        if (bytes > maxBytes) throw LabelPhotoTooLargeException()
        output.write(buffer, 0, read)
    }
}
