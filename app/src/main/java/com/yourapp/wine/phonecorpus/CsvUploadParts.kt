package com.yourapp.wine.phonecorpus

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** UTF-8 CSV byte splitter. Keeps quoted multiline records intact and repeats the exact header.
 * Decimal MB is deliberately stricter than MiB. A single oversize record is an explicit error,
 * never silently truncated. Input is untouched; only newly-created part files are cleaned on error.
 */
internal object CsvUploadParts {
    const val LIMIT_BYTES=25_000_000L
    fun split(source:File,directory:File,maxBytes:Long=LIMIT_BYTES):List<File> {
        require(maxBytes>0 && maxBytes<=Int.MAX_VALUE)
        if(source.length()<=maxBytes) return listOf(source)
        check(directory.isDirectory || directory.mkdirs())
        val parts=mutableListOf<File>()
        var output:OutputStream?=null
        try {
            source.inputStream().buffered().use { input ->
                val header=record(input,maxBytes) ?: error("CSV header missing")
                check(header.lastOrNull()==10.toByte()) { "CSV header must end with a newline" }
                check(header.size<maxBytes) { "CSV header exceeds upload limit" }
                var bytes=0L
                while(true) {
                    val row=record(input,maxBytes) ?: break
                    check(header.size.toLong()+row.size<=maxBytes) { "One CSV record plus header exceeds 25 MB; cannot split safely without changing data" }
                    if(output==null || bytes+row.size>maxBytes) {
                        output?.close()
                        val part=File(directory,"${source.nameWithoutExtension}-part-${(parts.size+1).toString().padStart(2,'0')}.csv")
                        check(part.createNewFile()) { "Export part already exists" }
                        parts+=part;output=part.outputStream().buffered();output!!.write(header);bytes=header.size.toLong()
                    }
                    output!!.write(row);bytes+=row.size
                }
            }
            output?.close();output=null
            check(parts.isNotEmpty()) { "No CSV data records" }
            check(parts.all { it.length()<=maxBytes })
            return parts
        } catch(e:Throwable) {
            try { output?.close() } finally { parts.forEach { it.delete() } }
            throw e
        }
    }
    private fun record(input:InputStream,maxBytes:Long):ByteArray? {
        val out=ByteArrayOutputStream();var quoted=false
        while(true) {
            val byte=input.read()
            if(byte<0) {
                check(!quoted) { "Unclosed quoted CSV field" }
                return if(out.size()==0) null else out.toByteArray()
            }
            check(out.size().toLong()<maxBytes) { "One CSV record exceeds 25 MB; cannot split safely" }
            out.write(byte)
            // Escaped quote pairs toggle twice; any intervening newline remains inside the field.
            if(byte==34) quoted=!quoted
            if(byte==10 && !quoted) return out.toByteArray()
        }
    }
}
