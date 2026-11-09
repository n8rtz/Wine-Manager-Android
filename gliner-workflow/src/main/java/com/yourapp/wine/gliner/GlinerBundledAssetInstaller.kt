package com.yourapp.wine.gliner

import java.io.File
import java.io.InputStream
import java.io.SequenceInputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Collections
import java.util.zip.GZIPInputStream

/** Installs bounded, pinned bundled assets atomically. No Android or network dependencies. */
internal object GlinerBundledAssetInstaller {
    fun install(openParts:List<()->InputStream>,encoding:String,target:File,expectedBytes:Long,expectedSha:String,checkActive:()->Unit = {}) {
        require(openParts.isNotEmpty() && expectedBytes>0 && expectedSha.matches(Regex("[0-9a-f]{64}")))
        require(encoding=="gzip" || encoding=="raw") { "Unsupported bundled asset encoding" }
        require(target.parentFile?.isDirectory==true) { "Model folder does not exist" }
        val temporary=File.createTempFile(".gliner-", ".part",target.parentFile)
        val opened=mutableListOf<InputStream>()
        try {
            openParts.forEach { opened += it() }
            val sequence=SequenceInputStream(Collections.enumeration(opened))
            val stream=if(encoding=="gzip") GZIPInputStream(sequence,65536) else sequence
            val digest=MessageDigest.getInstance("SHA-256")
            var size=0L
            stream.use { input -> temporary.outputStream().use { output ->
                val buffer=ByteArray(65536)
                while(true) {
                    checkActive()
                    val n=input.read(buffer)
                    if(n<0) break
                    size+=n
                    check(size<=expectedBytes) { "Bundled file exceeds pinned size" }
                    digest.update(buffer,0,n)
                    output.write(buffer,0,n)
                }
            } }
            val hash=digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            check(size==expectedBytes && hash==expectedSha) { "Bundled file checksum or size mismatch" }
            Files.move(temporary.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING)
        } finally {
            opened.forEach { runCatching { it.close() } }
            temporary.delete()
        }
    }
}
