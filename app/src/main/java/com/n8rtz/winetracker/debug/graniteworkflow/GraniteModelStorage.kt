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
     * Resolves the Granite GGUF file in `models/granite`.
     * Prefers `granite-4.2-3b-Q4_K_M.gguf`, or falls back to any `.gguf` file in the directory.
     */
    fun resolveGraniteGgufFile(context: Context): File {
        val dir = graniteDir(context)
        val canonical = File(dir, GRANITE_GGUF_FILENAME)
        if (canonical.isFile && canonical.length() > 0L) return canonical
        val fallback = dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".gguf", ignoreCase = true) && it.length() > 0L }
            ?.maxByOrNull { it.length() }
        return fallback ?: canonical
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

        val ggufValidHeader = if (ggufFile.isFile && ggufFile.length() >= 4L) {
            hasGgufMagic(ggufFile)
        } else {
            false
        }

        return WorkflowModelsStatus(
            graniteDirectoryPath = gDir.absolutePath,
            sharedPpOcrDirectoryPath = pDir.absolutePath,
            graniteGguf = ModelFileSlotStatus(
                slotName = "Granite 4.2 3B GGUF",
                fileName = ggufFile.name,
                absolutePath = ggufFile.absolutePath,
                present = ggufFile.isFile && ggufFile.length() > 0L,
                sizeBytes = if (ggufFile.isFile) ggufFile.length() else 0L,
                required = true,
                isBuiltInShared = false,
                notes = when {
                    !ggufFile.isFile -> "Place granite-4.2-3b-Q4_K_M.gguf in ${gDir.absolutePath}"
                    ggufValidHeader -> "Valid GGUF header verified"
                    else -> "File present (non-standard header)"
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
        val destDir = graniteDir(context)
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
