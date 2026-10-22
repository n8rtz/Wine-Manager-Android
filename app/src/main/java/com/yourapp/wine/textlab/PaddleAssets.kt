package com.yourapp.wine.textlab

import android.content.Context
import java.io.File
import java.security.MessageDigest

/** Byte-exact, local-only asset extraction shared by the two genuine ONNX models. */
internal object PaddleAssets {
    private fun hash(file:File):String {
        val digest=MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer=ByteArray(32768)
            while(true) { val n=input.read(buffer);if(n<0) break;digest.update(buffer,0,n) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    fun verified(context:Context,name:String,expected:String,limit:Long,folder:String="text-lab-paddle-v1"):File {
        val dir=File(context.noBackupFilesDir,folder).apply { check(isDirectory || mkdirs()) { "Cannot create private model directory" } }
        val target=File(dir,name)
        if(target.isFile && target.length()==limit && hash(target)==expected) return target
        val temp=File.createTempFile("model-",".tmp",dir)
        try {
            context.assets.open("$folder/$name").use { input -> temp.outputStream().use { output ->
                val buffer=ByteArray(32768);var size=0L
                while(true) { val n=input.read(buffer);if(n<0) break;size+=n;check(size<=limit) { "Asset $name exceeds pinned size $limit" };output.write(buffer,0,n) }
            } }
            check(temp.length()==limit && hash(temp)==expected) { "Bundled $name checksum/size mismatch: ${temp.length()} bytes; expected $limit" }
            check(temp.renameTo(target)) { "Cannot install verified asset $name" }
        } finally { temp.delete() }
        return target
    }
}
