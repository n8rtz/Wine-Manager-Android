package com.yourapp.wine.gliner

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GlinerBundledAssetInstallerTest {
    private fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun compressed(bytes:ByteArray)=ByteArrayOutputStream().also { out ->
        GZIPOutputStream(out).use { it.write(bytes) }
    }.toByteArray()

    @Test fun installsSplitGzipWithExactVerifiedBytes() {
        val dir=Files.createTempDirectory("gliner-install").toFile()
        try {
            val plain=ByteArray(16000) { (it%251).toByte() };val gzip=compressed(plain);val cut=gzip.size/2
            val dest=dir.resolve("model")
            GlinerBundledAssetInstaller.install(listOf({ ByteArrayInputStream(gzip.copyOfRange(0,cut)) },
                { ByteArrayInputStream(gzip.copyOfRange(cut,gzip.size)) }),"gzip",dest,plain.size.toLong(),sha(plain))
            assertArrayEquals(plain,dest.readBytes())
            assertEquals(listOf("model"),dir.listFiles()!!.map { it.name })
        } finally { dir.deleteRecursively() }
    }

    @Test fun invalidSizeOrHashNeverReplacesExistingModel() {
        val dir=Files.createTempDirectory("gliner-install").toFile()
        try {
            val target=dir.resolve("model");target.writeText("original")
            val bytes="replacement".toByteArray()
            assertThrows(IllegalStateException::class.java) {
                GlinerBundledAssetInstaller.install(listOf({ ByteArrayInputStream(bytes) }),"raw",target,
                    bytes.size.toLong(),"0".repeat(64))
            }
            assertEquals("original",target.readText())
            assertThrows(IllegalStateException::class.java) {
                GlinerBundledAssetInstaller.install(listOf({ ByteArrayInputStream(bytes) }),"raw",target,1,sha(bytes))
            }
            assertEquals("original",target.readText())
            assertEquals(listOf("model"),dir.listFiles()!!.map { it.name })
        } finally { dir.deleteRecursively() }
    }
    @Test fun cancellationRemovesTemporaryAndPreservesExistingModel() {
        val dir=Files.createTempDirectory("gliner-install").toFile()
        try {
            val target=dir.resolve("model");target.writeText("original")
            val bytes="replacement".toByteArray()
            assertThrows(java.util.concurrent.CancellationException::class.java) {
                GlinerBundledAssetInstaller.install(listOf({ ByteArrayInputStream(bytes) }),"raw",target,
                    bytes.size.toLong(),sha(bytes)) { throw java.util.concurrent.CancellationException("cancelled") }
            }
            assertEquals("original",target.readText())
            assertEquals(listOf("model"),dir.listFiles()!!.map { it.name })
        } finally { dir.deleteRecursively() }
    }
}
