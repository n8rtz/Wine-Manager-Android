package com.n8rtz.winetracker.debug.graniteworkflow

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Target model slot for one-tap Android file picker (`ActivityResultContracts.OpenDocument`) imports.
 */
enum class GraniteModelImportTarget(
    val title: String,
    val canonicalFileName: String
) {
    AUTO_DETECT("Auto-Detect (.gguf / .onnx / dict)", ""),
    GRANITE_GGUF("Granite 4.2 3B GGUF", GraniteModelStorage.GRANITE_GGUF_FILENAME),
    PPOCR_DET("PP-OCRv6 Detection ONNX", GraniteModelStorage.PPOCR_DET_FILENAME),
    PPOCR_CLS("PP-OCRv6 Angle Classifier ONNX", GraniteModelStorage.PPOCR_CLS_FILENAME),
    PPOCR_REC("PP-OCRv6 Recognition ONNX", GraniteModelStorage.PPOCR_REC_FILENAME),
    PPOCR_DICT("PP-OCRv6 Character Dictionary", GraniteModelStorage.PPOCR_DICT_FILENAME)
}

/**
 * Isolated model storage manager for the Dual OCR + Granite 4.2 workflow.
 *
 * Reads and writes exclusively under:
 * - `context.getExternalFilesDir("models/granite")`
 * - `context.getExternalFilesDir("models/ppocrv6")`
 */
object GraniteModelStorage {
    private const val TAG = "GraniteWorkflow"

    const val GRANITE_SUBDIR = "models/granite"
    const val PPOCR_SUBDIR = "models/ppocrv6"

    const val GRANITE_GGUF_FILENAME = "granite-4.2-3b-Q4_K_M.gguf"
    const val PPOCR_DET_FILENAME = "det.onnx"
    const val PPOCR_CLS_FILENAME = "cls.onnx"
    const val PPOCR_REC_FILENAME = "rec.onnx"
    const val PPOCR_DICT_FILENAME = "ppocrv6_dict.utf8"

    private const val BUNDLED_ASSET_FOLDER = "text-lab-paddle-v1"
    private const val BUNDLED_DET_ASSET = "v6-medium-det.onnx"
    private const val BUNDLED_REC_ASSET = "v6-medium-rec.onnx"
    private const val BUNDLED_DICT_ASSET = "ppocrv6_dict.utf8"

    fun graniteDir(context: Context): File {
        val dir = context.getExternalFilesDir(GRANITE_SUBDIR)
            ?: File(context.filesDir, GRANITE_SUBDIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun ppOcrDir(context: Context): File {
        val dir = context.getExternalFilesDir(PPOCR_SUBDIR)
            ?: File(context.filesDir, PPOCR_SUBDIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

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
     * Resolves the PP-OCRv6 detection ONNX model in `models/ppocrv6`.
     */
    fun resolvePpOcrDetFile(context: Context): File {
        val dir = ppOcrDir(context)
        val candidates = listOf(
            PPOCR_DET_FILENAME,
            "pp-ocrv6_det.onnx",
            "pp-ocrv6_medium_det.onnx",
            "v6-medium-det.onnx",
            "ch_PP-OCRv4_det_infer.onnx"
        )
        for (name in candidates) {
            val f = File(dir, name)
            if (f.isFile && f.length() > 0L) return f
        }
        val wildcard = dir.listFiles()?.firstOrNull {
            it.isFile && it.name.endsWith(".onnx", ignoreCase = true) &&
                it.name.contains("det", ignoreCase = true) && it.length() > 0L
        }
        return wildcard ?: File(dir, PPOCR_DET_FILENAME)
    }

    /**
     * Resolves the PP-OCRv6 angle classification ONNX model in `models/ppocrv6`.
     */
    fun resolvePpOcrClsFile(context: Context): File {
        val dir = ppOcrDir(context)
        val candidates = listOf(
            PPOCR_CLS_FILENAME,
            "pp-ocrv6_cls.onnx",
            "ch_ppocr_mobile_v2.0_cls_infer.onnx"
        )
        for (name in candidates) {
            val f = File(dir, name)
            if (f.isFile && f.length() > 0L) return f
        }
        val wildcard = dir.listFiles()?.firstOrNull {
            it.isFile && it.name.endsWith(".onnx", ignoreCase = true) &&
                it.name.contains("cls", ignoreCase = true) && it.length() > 0L
        }
        return wildcard ?: File(dir, PPOCR_CLS_FILENAME)
    }

    /**
     * Resolves the PP-OCRv6 recognition ONNX model in `models/ppocrv6`.
     */
    fun resolvePpOcrRecFile(context: Context): File {
        val dir = ppOcrDir(context)
        val candidates = listOf(
            PPOCR_REC_FILENAME,
            "pp-ocrv6_rec.onnx",
            "pp-ocrv6_medium_rec.onnx",
            "v6-medium-rec.onnx",
            "ch_PP-OCRv4_rec_infer.onnx"
        )
        for (name in candidates) {
            val f = File(dir, name)
            if (f.isFile && f.length() > 0L) return f
        }
        val wildcard = dir.listFiles()?.firstOrNull {
            it.isFile && it.name.endsWith(".onnx", ignoreCase = true) &&
                it.name.contains("rec", ignoreCase = true) && it.length() > 0L
        }
        return wildcard ?: File(dir, PPOCR_REC_FILENAME)
    }

    /**
     * Resolves the PP-OCRv6 character dictionary in `models/ppocrv6`.
     * If not yet in external storage, automatically seeds `ppocrv6_dict.utf8` from app assets if present.
     */
    fun resolvePpOcrDictFile(context: Context, autoSeedFromAssets: Boolean = true): File {
        val dir = ppOcrDir(context)
        val candidates = listOf(PPOCR_DICT_FILENAME, "dict.txt", "ppocr_keys_v1.txt")
        for (name in candidates) {
            val f = File(dir, name)
            if (f.isFile && f.length() > 0L) return f
        }
        val wildcard = dir.listFiles()?.firstOrNull {
            it.isFile && (it.name.endsWith(".utf8", ignoreCase = true) || it.name.endsWith(".txt", ignoreCase = true)) &&
                it.length() > 0L
        }
        if (wildcard != null) return wildcard

        val target = File(dir, PPOCR_DICT_FILENAME)
        if (autoSeedFromAssets) {
            runCatching {
                context.assets.open("$BUNDLED_ASSET_FOLDER/$BUNDLED_DICT_ASSET").use { input ->
                    FileOutputStream(target).use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
        return target
    }

    /**
     * Checks whether the APK has bundled PP-OCRv6 assets that can be copied with one tap.
     */
    fun hasBundledPpOcrAssets(context: Context): Boolean = runCatching {
        val assets = context.assets.list(BUNDLED_ASSET_FOLDER).orEmpty().toSet()
        BUNDLED_DET_ASSET in assets && BUNDLED_REC_ASSET in assets && BUNDLED_DICT_ASSET in assets
    }.getOrDefault(false)

    /**
     * Copies bundled PP-OCRv6 ONNX models (`v6-medium-det.onnx` -> `det.onnx`, `v6-medium-rec.onnx` -> `rec.onnx`,
     * and `ppocrv6_dict.utf8`) into `context.getExternalFilesDir("models/ppocrv6")`.
     */
    fun seedPpOcrFromBundledAssets(context: Context): Result<Int> = runCatching {
        val dir = ppOcrDir(context)
        var copiedCount = 0
        val mappings = listOf(
            BUNDLED_DET_ASSET to PPOCR_DET_FILENAME,
            BUNDLED_REC_ASSET to PPOCR_REC_FILENAME,
            BUNDLED_DICT_ASSET to PPOCR_DICT_FILENAME
        )
        for ((assetName, targetName) in mappings) {
            val target = File(dir, targetName)
            val temp = File(dir, "$targetName.tmp")
            try {
                context.assets.open("$BUNDLED_ASSET_FOLDER/$assetName").use { input ->
                    FileOutputStream(temp).use { output ->
                        input.copyTo(output, bufferSize = 64 * 1024)
                    }
                }
                if (target.exists()) target.delete()
                check(temp.renameTo(target)) { "Failed to move $targetName into ${dir.absolutePath}" }
                copiedCount++
            } finally {
                if (temp.exists()) temp.delete()
            }
        }
        copiedCount
    }

    /**
     * Inspects current model files on device without throwing or crashing.
     */
    fun inspectModels(context: Context): WorkflowModelsStatus {
        val gDir = graniteDir(context)
        val pDir = ppOcrDir(context)

        val ggufFile = resolveGraniteGgufFile(context)
        val detFile = resolvePpOcrDetFile(context)
        val clsFile = resolvePpOcrClsFile(context)
        val recFile = resolvePpOcrRecFile(context)
        val dictFile = resolvePpOcrDictFile(context, autoSeedFromAssets = true)

        val ggufValidHeader = if (ggufFile.isFile && ggufFile.length() >= 4L) {
            hasGgufMagic(ggufFile)
        } else {
            false
        }

        return WorkflowModelsStatus(
            graniteDirectoryPath = gDir.absolutePath,
            ppOcrDirectoryPath = pDir.absolutePath,
            graniteGguf = ModelFileSlotStatus(
                slotName = "Granite 4.2 3B GGUF",
                fileName = ggufFile.name,
                absolutePath = ggufFile.absolutePath,
                present = ggufFile.isFile && ggufFile.length() > 0L,
                sizeBytes = if (ggufFile.isFile) ggufFile.length() else 0L,
                required = true,
                notes = when {
                    !ggufFile.isFile -> "Place granite-4.2-3b-Q4_K_M.gguf in ${gDir.absolutePath}"
                    ggufValidHeader -> "Valid GGUF header verified"
                    else -> "File present (non-standard header)"
                }
            ),
            ppOcrDetOnnx = ModelFileSlotStatus(
                slotName = "PP-OCRv6 Detection (det.onnx)",
                fileName = detFile.name,
                absolutePath = detFile.absolutePath,
                present = detFile.isFile && detFile.length() > 0L,
                sizeBytes = if (detFile.isFile) detFile.length() else 0L,
                required = true
            ),
            ppOcrClsOnnx = ModelFileSlotStatus(
                slotName = "PP-OCRv6 Angle Classifier (cls.onnx)",
                fileName = clsFile.name,
                absolutePath = clsFile.absolutePath,
                present = clsFile.isFile && clsFile.length() > 0L,
                sizeBytes = if (clsFile.isFile) clsFile.length() else 0L,
                required = false,
                notes = if (clsFile.isFile && clsFile.length() > 0L) "Active (0°/180° check)" else "Optional (upright default)"
            ),
            ppOcrRecOnnx = ModelFileSlotStatus(
                slotName = "PP-OCRv6 Recognition (rec.onnx)",
                fileName = recFile.name,
                absolutePath = recFile.absolutePath,
                present = recFile.isFile && recFile.length() > 0L,
                sizeBytes = if (recFile.isFile) recFile.length() else 0L,
                required = true
            ),
            ppOcrDict = ModelFileSlotStatus(
                slotName = "PP-OCRv6 Dictionary (ppocrv6_dict.utf8)",
                fileName = dictFile.name,
                absolutePath = dictFile.absolutePath,
                present = dictFile.isFile && dictFile.length() > 0L,
                sizeBytes = if (dictFile.isFile) dictFile.length() else 0L,
                required = true
            ),
            bundledPaddleAssetsAvailable = hasBundledPpOcrAssets(context),
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
     * Imports a user-selected document URI into the appropriate external model directory.
     */
    fun importModelUri(
        context: Context,
        uri: Uri,
        target: GraniteModelImportTarget
    ): Result<File> = runCatching {
        val displayName = queryDisplayName(context, uri)?.trim().orEmpty()
        val resolvedTarget = if (target == GraniteModelImportTarget.AUTO_DETECT) {
            inferTargetFromName(displayName, uri.lastPathSegment.orEmpty())
        } else {
            target
        }

        val (destDir, destName) = when (resolvedTarget) {
            GraniteModelImportTarget.GRANITE_GGUF -> graniteDir(context) to GRANITE_GGUF_FILENAME
            GraniteModelImportTarget.PPOCR_DET -> ppOcrDir(context) to PPOCR_DET_FILENAME
            GraniteModelImportTarget.PPOCR_CLS -> ppOcrDir(context) to PPOCR_CLS_FILENAME
            GraniteModelImportTarget.PPOCR_REC -> ppOcrDir(context) to PPOCR_REC_FILENAME
            GraniteModelImportTarget.PPOCR_DICT -> ppOcrDir(context) to PPOCR_DICT_FILENAME
            GraniteModelImportTarget.AUTO_DETECT -> error(
                "Could not determine model slot for '$displayName'. Expected a .gguf, *det*.onnx, *cls*.onnx, *rec*.onnx, or dict file."
            )
        }

        val destFile = File(destDir, destName)
        val tempFile = File(destDir, "$destName.import.tmp")
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
                "Failed to save imported model to ${destFile.absolutePath}"
            }
            Log.d(TAG, "Imported model '$displayName' -> ${destFile.absolutePath} (${destFile.length()} bytes)")
            destFile
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    internal fun inferTargetFromName(displayName: String, fallbackSegment: String): GraniteModelImportTarget {
        val name = (displayName.ifBlank { fallbackSegment }).lowercase()
        return when {
            name.endsWith(".gguf") || name.contains("granite") -> GraniteModelImportTarget.GRANITE_GGUF
            name.endsWith(".onnx") && name.contains("det") -> GraniteModelImportTarget.PPOCR_DET
            name.endsWith(".onnx") && name.contains("cls") -> GraniteModelImportTarget.PPOCR_CLS
            name.endsWith(".onnx") && name.contains("rec") -> GraniteModelImportTarget.PPOCR_REC
            name.endsWith(".utf8") || name.contains("dict") || name.endsWith(".txt") -> GraniteModelImportTarget.PPOCR_DICT
            else -> GraniteModelImportTarget.AUTO_DETECT
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
