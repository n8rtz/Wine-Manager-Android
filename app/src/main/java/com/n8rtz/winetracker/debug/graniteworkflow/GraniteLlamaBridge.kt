package com.n8rtz.winetracker.debug.graniteworkflow

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Isolated `llama.cpp` JNI wrapper for loading and running `granite-4.2-3b-Q4_K_M.gguf`
 * from `context.getExternalFilesDir("models/granite")`.
 *
 * Completely decoupled from production app classes. If `libgranite_llama_jni.so` is not bundled
 * in the current APK build, [isNativeLibraryLoaded] returns `false` without crashing.
 */
object GraniteLlamaBridge : AutoCloseable {
    private const val TAG = "GraniteLlamaBridge"
    private const val JNI_LIB_NAME = "granite_llama_jni"

    private val mutex = Mutex()
    private var modelHandle: Long = 0L
    private var loadedModelPath: String? = null
    @Volatile
    private var lastErrorMessage: String = ""
    @Volatile
    var kotlinStageNote: String = ""

    private val nativeLoaded: Boolean by lazy {
        runCatching {
            System.loadLibrary(JNI_LIB_NAME)
            Log.d(TAG, "Loaded native library lib$JNI_LIB_NAME.so")
            true
        }.getOrElse { err ->
            lastErrorMessage = err.message ?: err.javaClass.simpleName
            Log.i(TAG, "lib$JNI_LIB_NAME.so not present in APK (${err.message}); JNI bridge inactive")
            false
        }
    }

    fun isNativeLibraryLoaded(): Boolean = nativeLoaded

    fun lastNativeError(): String {
        if (!nativeLoaded) return lastErrorMessage
        val nativeErr = runCatching { nativeGetLastError() }.getOrDefault("").trim()
        return nativeErr.ifEmpty { lastErrorMessage }
    }

    fun currentProgress(): String {
        val ktNote = kotlinStageNote
        if (ktNote.isNotBlank()) return ktNote
        if (!nativeLoaded) return ""
        return runCatching { nativeGetProgressStatus() }.getOrDefault("").trim()
    }

    fun requestAbort() {
        if (nativeLoaded) {
            runCatching { nativeRequestAbort(true) }
        }
    }

    private val optimalDecodeThreads: Int by lazy {
        runCatching {
            val freqs = (0 until 16).mapNotNull { idx ->
                val f = File("/sys/devices/system/cpu/cpu$idx/cpufreq/cpuinfo_max_freq")
                if (f.canRead()) f.readText().trim().toLongOrNull() else null
            }.filter { it > 0L }
            if (freqs.size >= 4) {
                val minFreq = freqs.minOrNull() ?: 0L
                val maxFreq = freqs.maxOrNull() ?: 0L
                val fastCount = if (minFreq >= 2_500_000L || maxFreq <= (minFreq * 115L) / 100L) {
                    freqs.size.coerceAtMost(6)
                } else {
                    freqs.count { it > (minFreq * 112L) / 100L }
                }
                fastCount.coerceIn(4, 6)
            } else {
                val cores = Runtime.getRuntime().availableProcessors()
                if (cores >= 8) 5 else (cores / 2).coerceIn(2, 4)
            }
        }.getOrElse {
            val cores = Runtime.getRuntime().availableProcessors()
            if (cores >= 8) 5 else (cores / 2).coerceIn(2, 4)
        }
    }

    suspend fun generateWithGguf(
        ggufFile: File,
        prompt: String,
        gbnfGrammar: String,
        maxTokens: Int = 256,
        temperature: Float = 0.0f,
        nCtx: Int = 3072,
        nThreads: Int = optimalDecodeThreads
    ): String = mutex.withLock {
        check(nativeLoaded) { "lib$JNI_LIB_NAME.so is not loaded" }
        check(ggufFile.isFile && ggufFile.length() > 0L) {
            "GGUF model file missing: ${ggufFile.absolutePath}"
        }

        val pathKey = "${ggufFile.absolutePath}:${ggufFile.length()}:${ggufFile.lastModified()}"
        if (modelHandle == 0L || loadedModelPath != pathKey) {
            if (modelHandle != 0L) {
                runCatching { nativeFreeModel(modelHandle) }
                modelHandle = 0L
                loadedModelPath = null
            }
            val handle = nativeInitModel(ggufFile.absolutePath, nCtx, nThreads)
            if (handle == 0L) {
                val errDetail = runCatching { nativeGetLastError() }.getOrDefault("").trim()
                lastErrorMessage = errDetail.ifEmpty { "nativeInitModel returned 0L" }
                error("llama.cpp failed to initialize GGUF model at ${ggufFile.name}: $lastErrorMessage")
            }
            modelHandle = handle
            loadedModelPath = pathKey
        }

        val out = nativeComplete(
            modelHandle = modelHandle,
            prompt = prompt,
            gbnfGrammar = gbnfGrammar,
            maxTokens = maxTokens,
            temperature = temperature
        )
        if (out.isBlank()) {
            val errDetail = runCatching { nativeGetLastError() }.getOrDefault("").trim()
            if (errDetail.isNotEmpty()) {
                lastErrorMessage = errDetail
            }
        }
        out
    }

    override fun close() {
        if (nativeLoaded && modelHandle != 0L) {
            runCatching { nativeFreeModel(modelHandle) }
            modelHandle = 0L
            loadedModelPath = null
        }
    }

    @JvmStatic
    private external fun nativeGetLastError(): String

    @JvmStatic
    private external fun nativeGetProgressStatus(): String

    @JvmStatic
    private external fun nativeRequestAbort(abort: Boolean)

    @JvmStatic
    private external fun nativeInitModel(
        modelPath: String,
        nCtx: Int,
        nThreads: Int
    ): Long

    @JvmStatic
    private external fun nativeComplete(
        modelHandle: Long,
        prompt: String,
        gbnfGrammar: String,
        maxTokens: Int,
        temperature: Float
    ): String

    @JvmStatic
    private external fun nativeFreeModel(modelHandle: Long)
}
