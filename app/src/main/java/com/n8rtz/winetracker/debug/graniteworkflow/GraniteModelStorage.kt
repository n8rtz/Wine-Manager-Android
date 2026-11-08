package com.n8rtz.winetracker.debug.graniteworkflow

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.yourapp.wine.textlab.PaddleCandidates
import java.io.File
import java.io.FileOutputStream

/**
 * Target model slot for one-tap Android file picker (`ActivityResultContracts.OpenDocument`) imports.
 *
 * ML Kit and PP-OCRv6 reuse the app's existing bundled models (`MlKitOcrEngine` and
 * `text-lab-paddle-v1/v6-medium-det.onnx` + `v6-medium-rec.onnx` + `ppocrv6_dict.utf8`),
 * so only the Granite GGUF file needs to be imported.
 */
enum class GraniteModelImportTarget(
    val title: String,
    val canonicalFileName: String
) {
    GRANITE_GGUF("Granite 4.2 3B GGUF", GraniteModelStorage.GRANITE_GGUF_FILENAME)
}

/**
 * Model storage manager for the Dual OCR + Granite 4.2 workflow.
 *
 * - **Granite 4.2 3B (`granite-4.2-3b-Q4_K_M.gguf`):** Loaded from `context.getExternalFilesDir("models/granite")`
 *   (or imported via the UI file picker).
 * - **PP-OCRv6 & ML Kit:** Reuses the existing bundled `text-lab-paddle-v1` files (`v6-medium-det.onnx`,
 *   `v6-medium-rec.onnx`, `ppocrv6_dict.utf8` in `File(context.noBackupFilesDir, "text-lab-paddle-v1")`)
 *   and `MlKitOcrEngine` without downloading or copying second copies.
 */
object GraniteModelStorage {
    private const val TAG = "GraniteWorkflow"

    const val GRANITE_SUBDIR = "models/granite"
    const val SHARED_PADDLE_FOLDER = "text-lab-paddle-v1"
    const val GRANITE_GGUF_FILENAME = "granite-4.2-3b-Q4_K_M.gguf"
    const val MIN_VALID_GGUF_BYTES = 64L * 1024L * 1024L // Real 3B Q4_K_M GGUF is ~1.9–2.5 GB
    const val PRODUCER_EXPANDED_ASSET_DIR = "producer-gazetteer-expanded-v1"
    const val PRODUCER_EXPANDED_DB_FILENAME = "producers-expanded.sqlite"
    const val PRODUCER_V1_ASSET_DIR = "producer-gazetteer-v1"
    const val LINKED_WINES_DB_FILENAME = "wines.sqlite"

    fun internalGraniteDir(context: Context): File {
        val dir = File(context.noBackupFilesDir, GRANITE_SUBDIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun graniteDir(context: Context): File {
        val dir = context.getExternalFilesDir(GRANITE_SUBDIR)
            ?: File(context.filesDir, GRANITE_SUBDIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun sharedPaddleDir(context: Context): File =
        File(context.noBackupFilesDir, SHARED_PADDLE_FOLDER)

    /**
     * Resolves the Granite GGUF file, preferring `noBackupFilesDir/models/granite` (direct ext4/f2fs
     * without Android `/storage/emulated/0` FUSE mmap overhead) and falling back to `getExternalFilesDir("models/granite")`.
     */
    fun resolveGraniteGgufFile(context: Context): File {
        val internalDir = internalGraniteDir(context)
        val internalCanonical = File(internalDir, GRANITE_GGUF_FILENAME)
        if (internalCanonical.isFile && internalCanonical.length() >= MIN_VALID_GGUF_BYTES) {
            return internalCanonical
        }
        val internalAny = internalDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".gguf", ignoreCase = true) && it.length() >= MIN_VALID_GGUF_BYTES }
            ?.maxByOrNull { it.length() }
        if (internalAny != null) return internalAny

        val dir = graniteDir(context)
        val canonical = File(dir, GRANITE_GGUF_FILENAME)
        if (canonical.isFile && canonical.length() > 0L) return canonical
        val fallback = dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".gguf", ignoreCase = true) && it.length() > 0L }
            ?.maxByOrNull { it.length() }
        return fallback ?: internalCanonical
    }

    /**
     * If the resolved GGUF file currently sits on `/storage/emulated/` (Android FUSE daemon, which
     * severely penalizes 4 KB mmap page faults on 2 GB models), copies it once into
     * `noBackupFilesDir/models/granite` (direct kernel ext4/f2fs) when space permits.
     */
    fun ensureDirectFilesystemGguf(context: Context, currentFile: File): File {
        if (!currentFile.isFile || currentFile.length() < MIN_VALID_GGUF_BYTES) return currentFile
        val internalDir = internalGraniteDir(context)
        if (currentFile.absolutePath.startsWith(internalDir.absolutePath)) return currentFile
        val target = File(internalDir, GRANITE_GGUF_FILENAME)
        if (target.isFile && target.length() == currentFile.length() && hasGgufMagic(target)) {
            return target
        }
        val requiredFree = currentFile.length() + 384L * 1024L * 1024L
        if (internalDir.usableSpace < requiredFree) {
            return currentFile
        }
        return runCatching {
            val totalBytes = currentFile.length().coerceAtLeast(1L)
            val totalMb = totalBytes / (1024L * 1024L)
            val tmp = File(internalDir, "$GRANITE_GGUF_FILENAME.stage.tmp")
            try {
                GraniteLlamaBridge.kotlinStageNote = "staging GGUF to internal storage (0% · 0/$totalMb MB)..."
                currentFile.inputStream().use { input ->
                    FileOutputStream(tmp).use { output ->
                        val buf = ByteArray(512 * 1024)
                        var copied = 0L
                        var nextReport = 32L * 1024L * 1024L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            copied += n
                            if (copied >= nextReport || copied == totalBytes) {
                                val pct = ((copied * 100L) / totalBytes).coerceIn(0L, 100L)
                                val doneMb = copied / (1024L * 1024L)
                                GraniteLlamaBridge.kotlinStageNote =
                                    "staging GGUF to internal storage ($pct% · $doneMb/$totalMb MB)..."
                                nextReport = copied + 32L * 1024L * 1024L
                            }
                        }
                        output.flush()
                    }
                }
                if (target.exists()) target.delete()
                if (tmp.renameTo(target)) {
                    // Remove duplicate FUSE copy once safely staged on internal ext4/f2fs
                    runCatching { currentFile.delete() }
                    target
                } else {
                    tmp.delete()
                    currentFile
                }
            } finally {
                GraniteLlamaBridge.kotlinStageNote = ""
            }
        }.getOrDefault(currentFile)
    }

    /**
     * Checks whether the existing PP-OCRv6 files (`v6-medium-det.onnx`, `v6-medium-rec.onnx`,
     * `ppocrv6_dict.utf8`) are present in the APK assets or already extracted in `noBackupFilesDir/text-lab-paddle-v1`.
     */
    fun inspectModels(context: Context): WorkflowModelsStatus {
        val gDir = graniteDir(context)
        val pDir = sharedPaddleDir(context)
        val ggufFile = resolveGraniteGgufFile(context)

        val spec = PaddleCandidates.all.first()
        val bundledAssets = runCatching {
            context.assets.list(SHARED_PADDLE_FOLDER).orEmpty().toSet()
        }.getOrDefault(emptySet())

        val extractedDet = File(pDir, spec.detector.asset)
        val extractedRec = File(pDir, spec.recognizer.asset)
        val extractedDict = File(pDir, spec.dictionary.asset)

        val detAvailable = (extractedDet.isFile && extractedDet.length() == spec.detector.bytes) ||
            (spec.detector.asset in bundledAssets)
        val recAvailable = (extractedRec.isFile && extractedRec.length() == spec.recognizer.bytes) ||
            (spec.recognizer.asset in bundledAssets)
        val dictAvailable = (extractedDict.isFile && extractedDict.length() == spec.dictionary.bytes) ||
            (spec.dictionary.asset in bundledAssets)

        val fileExists = ggufFile.isFile && ggufFile.length() > 0L
        val ggufValidHeader = if (fileExists && ggufFile.length() >= 4L) {
            hasGgufMagic(ggufFile)
        } else {
            false
        }
        val ggufValidSize = fileExists && ggufFile.length() >= MIN_VALID_GGUF_BYTES
        val ggufReady = fileExists && ggufValidHeader && ggufValidSize

        return WorkflowModelsStatus(
            graniteDirectoryPath = gDir.absolutePath,
            sharedPpOcrDirectoryPath = pDir.absolutePath,
            graniteGguf = ModelFileSlotStatus(
                slotName = "Granite 4.2 3B GGUF",
                fileName = ggufFile.name,
                absolutePath = ggufFile.absolutePath,
                present = ggufReady,
                sizeBytes = if (fileExists) ggufFile.length() else 0L,
                required = true,
                isBuiltInShared = false,
                notes = when {
                    !fileExists -> "Place granite-4.2-3b-Q4_K_M.gguf (~2 GB) in ${gDir.absolutePath}"
                    !ggufValidHeader -> "Invalid file (${String.format(java.util.Locale.US, "%.1f MB", ggufFile.length() / (1024.0 * 1024.0))}): missing 'GGUF' magic header — tap Reset cache and import the real .gguf weights"
                    !ggufValidSize -> "Incomplete/truncated GGUF (${String.format(java.util.Locale.US, "%.1f MB", ggufFile.length() / (1024.0 * 1024.0))}): expected ~1.9–2.5 GB Q4_K_M model weights"
                    else -> "Valid GGUF header & model size verified"
                }
            ),
            mlKitEngine = ModelFileSlotStatus(
                slotName = "Google ML Kit v2 (Latin)",
                fileName = "MlKitOcrEngine (bundled)",
                absolutePath = "APK bundled",
                present = true,
                sizeBytes = 0L,
                required = true,
                isBuiltInShared = true,
                notes = "Reuses existing MlKitOcrEngine (no download needed)"
            ),
            ppOcrDetOnnx = ModelFileSlotStatus(
                slotName = "PP-OCRv6 Detection (${spec.detector.name})",
                fileName = spec.detector.asset,
                absolutePath = extractedDet.absolutePath,
                present = detAvailable,
                sizeBytes = spec.detector.bytes,
                required = true,
                isBuiltInShared = true,
                notes = if (extractedDet.isFile) "Shared in noBackupFilesDir" else "Bundled in text-lab-paddle-v1"
            ),
            ppOcrRecOnnx = ModelFileSlotStatus(
                slotName = "PP-OCRv6 Recognition (${spec.recognizer.name})",
                fileName = spec.recognizer.asset,
                absolutePath = extractedRec.absolutePath,
                present = recAvailable,
                sizeBytes = spec.recognizer.bytes,
                required = true,
                isBuiltInShared = true,
                notes = if (extractedRec.isFile) "Shared in noBackupFilesDir" else "Bundled in text-lab-paddle-v1"
            ),
            ppOcrDict = ModelFileSlotStatus(
                slotName = "PP-OCRv6 Dictionary (${spec.dictionary.entries} chars)",
                fileName = spec.dictionary.asset,
                absolutePath = extractedDict.absolutePath,
                present = dictAvailable,
                sizeBytes = spec.dictionary.bytes,
                required = true,
                isBuiltInShared = true,
                notes = "Reuses existing ppocrv6_dict.utf8"
            ),
            producerGazetteerDb = ModelFileSlotStatus(
                slotName = "Producer Gazetteer SQLite DB (Expanded)",
                fileName = PRODUCER_EXPANDED_DB_FILENAME,
                absolutePath = File(context.noBackupFilesDir, "$PRODUCER_EXPANDED_ASSET_DIR/$PRODUCER_EXPANDED_DB_FILENAME").absolutePath,
                present = true,
                sizeBytes = 6_471_680L,
                required = false,
                isBuiltInShared = true,
                notes = "Shared LWIN + supplemental producer FTS5 trigram SQLite DB (same as GLiNER)"
            ),
            linkedWineDb = ModelFileSlotStatus(
                slotName = "Linked Wine & Cuvée SQLite DB (LWIN7)",
                fileName = LINKED_WINES_DB_FILENAME,
                absolutePath = File(context.noBackupFilesDir, "$PRODUCER_V1_ASSET_DIR/$LINKED_WINES_DB_FILENAME").absolutePath,
                present = true,
                sizeBytes = 68_517_888L,
                required = false,
                isBuiltInShared = true,
                notes = "Shared producer-linked LWIN wine FTS5 trigram SQLite DB (same as GLiNER)"
            ),
            appellationCatalogDb = ModelFileSlotStatus(
                slotName = "Appellations, Grapes & Geography KB",
                fileName = "SqliteWineKnowledgeBase + ReferenceWineCatalog + BundledOrigins",
                absolutePath = "APK bundled + SQLite KB",
                present = true,
                sizeBytes = 0L,
                required = false,
                isBuiltInShared = true,
                notes = "Shared French AOC, Italian DOC/DOCG, US AVA, grapes & styles KB (same as GLiNER)"
            ),
            nativeLlamaJniLoaded = GraniteLlamaBridge.isNativeLibraryLoaded()
        )
    }

    /**
     * Checks whether a file starts with the 4-byte ASCII magic `"GGUF"`.
     */
    fun hasGgufMagic(file: File): Boolean = runCatching {
        if (!file.isFile || file.length() < 4L) return false
        val header = ByteArray(4)
        file.inputStream().use { input ->
            if (input.read(header) != 4) return false
        }
        header[0] == 'G'.code.toByte() &&
            header[1] == 'G'.code.toByte() &&
            header[2] == 'U'.code.toByte() &&
            header[3] == 'F'.code.toByte()
    }.getOrDefault(false)

    /**
     * Imports a user-selected Granite `.gguf` URI into `context.getExternalFilesDir("models/granite")`.
     */
    fun importGraniteGgufUri(
        context: Context,
        uri: Uri
    ): Result<File> = runCatching {
        val displayName = queryDisplayName(context, uri)?.trim().orEmpty()
        val internalDir = internalGraniteDir(context)
        val destDir = if (internalDir.usableSpace >= 3L * 1024L * 1024L * 1024L) {
            internalDir
        } else {
            graniteDir(context)
        }
        val destFile = File(destDir, GRANITE_GGUF_FILENAME)
        val tempFile = File(destDir, "$GRANITE_GGUF_FILENAME.import.tmp")
        try {
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: error("Cannot open selected file URI: $uri")
            inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(128 * 1024)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        total += read
                    }
                    output.flush()
                    check(total > 0L) { "Selected model file is empty (0 bytes)" }
                }
            }
            check(hasGgufMagic(tempFile)) {
                val preview = readFirstBytesAscii(tempFile, 8)
                "Selected file '${displayName.ifEmpty { uri.lastPathSegment.orEmpty() }}' (${String.format(java.util.Locale.US, "%.1f MB", tempFile.length() / (1024.0 * 1024.0))}) is NOT a valid GGUF file (starts with '$preview', expected 'GGUF'). Make sure it is not a .zip/.gz archive, HTML page, or partial download."
            }
            check(tempFile.length() >= MIN_VALID_GGUF_BYTES) {
                "Selected GGUF file '${displayName.ifEmpty { uri.lastPathSegment.orEmpty() }}' is only ${String.format(java.util.Locale.US, "%.1f MB", tempFile.length() / (1024.0 * 1024.0))} (likely an imatrix file or interrupted download). Import the full ~1.9–2.5 GB Q4_K_M .gguf weights."
            }
            if (destFile.exists()) {
                destFile.delete()
            }
            check(tempFile.renameTo(destFile)) {
                "Failed to save imported GGUF model to ${destFile.absolutePath}"
            }
            Log.d(TAG, "Imported Granite GGUF '$displayName' -> ${destFile.absolutePath} (${destFile.length()} bytes)")
            destFile
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    /**
     * Removes any imported Granite `.gguf` file from `models/granite`.
     * Shared PP-OCRv6 and ML Kit models are preserved.
     */
    fun clearGraniteModelCache(context: Context): Result<Unit> = runCatching {
        listOf(internalGraniteDir(context), graniteDir(context)).forEach { dir ->
            dir.listFiles()?.forEach { file ->
                if (file.isFile && (file.name.endsWith(".gguf", ignoreCase = true) || file.name.endsWith(".tmp"))) {
                    file.delete()
                }
            }
        }
    }

    private fun readFirstBytesAscii(file: File, count: Int): String = runCatching {
        val buf = ByteArray(count)
        val n = file.inputStream().use { it.read(buf) }
        if (n <= 0) return ""
        buf.take(n).joinToString("") { b ->
            val c = b.toInt() and 0xFF
            if (c in 32..126) c.toChar().toString() else String.format("\\x%02x", c)
        }
    }.getOrDefault("")

    private fun queryDisplayName(context: Context, uri: Uri): String? = runCatching {
        val cursor: Cursor? = context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )
        cursor?.use {
            if (it.moveToFirst()) {
                val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) it.getString(idx) else null
            } else null
        }
    }.getOrNull()
}
