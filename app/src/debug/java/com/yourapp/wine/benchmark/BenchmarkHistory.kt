package com.yourapp.wine.benchmark

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream

/** Durable, private, bounded history. Atomic row files retain completed cases even after process death. */
class BenchmarkHistory(private val root:File) {
    suspend fun save(key:String,row:String) = withContext(Dispatchers.IO) { lock.withLock {
        require(key.matches(Regex("[A-Za-z0-9_.-]+")))
        check(root.mkdirs() || root.isDirectory)
        // Under the process-wide lock no other row writer is active; discard only crash-leftover staging files.
        root.listFiles()?.filter { it.extension=="pending" }?.forEach { it.delete() }
        val target=File(root,"$key.row")
        val bytes=row.toByteArray(Charsets.UTF_8)
        check(rows().sumOf { it.length() }-target.length()+bytes.size<=MAX_BYTES) { "OCR history is full; export and clear it before running more tests" }
        val temporary=File(root,"$key.pending")
        try {
            temporary.outputStream().use { it.write(bytes);it.fd.sync() }
            currentCoroutineContext().ensureActive()
            check(temporary.renameTo(target)) { "Could not save benchmark row" }
        } finally { temporary.delete() }
    } }
    suspend fun count():Int=withContext(Dispatchers.IO) { lock.withLock { rows().size } }
    suspend fun export(output:OutputStream,batch:String?=null)=withContext(Dispatchers.IO) { lock.withLock {
        output.write(BenchmarkCsv.header.toByteArray(Charsets.UTF_8))
        rows().filter { batch==null || it.name.startsWith("$batch-") }.forEach { file ->
            currentCoroutineContext().ensureActive()
            file.inputStream().use { it.copyTo(output) }
        }
        output.flush()
    } }
    suspend fun clear()=withContext(Dispatchers.IO) { lock.withLock {
        root.listFiles()?.filter { it.extension in setOf("row","pending") }?.forEach { check(it.delete()) }
        Unit
    } }
    private fun rows()=root.listFiles()?.filter { it.extension=="row" }?.sortedBy { it.name }.orEmpty()
    companion object { private val lock=Mutex();const val MAX_BYTES=64L*1024*1024 }
}
