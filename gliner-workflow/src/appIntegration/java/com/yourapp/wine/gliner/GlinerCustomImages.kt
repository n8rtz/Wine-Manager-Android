package com.yourapp.wine.gliner

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Private copies of user-selected images. Neither reference answers nor image bytes are exported. */
internal object GlinerCustomImages {
    private const val FOLDER="gliner-custom-images"
    private const val MAX_BYTES=64L*1024*1024
    private const val MAX_PIXELS=100_000_000L // Same limit as CaptureDecoder.
    private val idPattern=Regex("custom-[0-9a-f]{64}\\.img")

    data class ImportResult(val samples:List<GlinerLabSample>,val added:Int,val duplicates:Int,val errors:List<String>)

    fun file(context:Context,id:String):File {
        require(idPattern.matches(id)) { "Invalid custom image ID" }
        return File(File(context.noBackupFilesDir,FOLDER),id)
    }

    private fun metadata(context:Context)=AtomicFile(File(File(context.noBackupFilesDir,FOLDER),"images.json"))

    fun load(context:Context):List<GlinerLabSample> {
        val index=metadata(context)
        if(!index.baseFile.exists() && !File(index.baseFile.path+".bak").exists()) return emptyList()
        val entries=JSONObject(index.openRead().bufferedReader().use { it.readText() }).getJSONArray("images")
        return (0 until entries.length()).map { indexValue ->
            val entry=entries.getJSONObject(indexValue)
            val id=entry.getString("id")
            val image=file(context,id)
            require(image.isFile) { "Imported image is missing: ${entry.optString("name",id)}" }
            GlinerLabSample(image=id,reviewNumber=0,sourceFilename=entry.getString("name"),
                sha256=entry.getString("sha256"),included=true,targets=emptyMap(),isCustom=true,
                matchesCorpus=entry.optBoolean("matches_corpus",false))
        }
    }

    private fun save(context:Context,samples:List<GlinerLabSample>) {
        val dir=File(context.noBackupFilesDir,FOLDER)
        check(dir.exists() || dir.mkdirs()) { "Cannot create private image folder" }
        val json=JSONObject().put("images",JSONArray(samples.map { sample -> JSONObject().apply {
            put("id",sample.image); put("name",sample.sourceFilename); put("sha256",sample.sha256)
            put("matches_corpus",sample.matchesCorpus)
        } })).toString()
        val index=metadata(context)
        val stream=index.startWrite()
        try {
            stream.write(json.toByteArray(Charsets.UTF_8))
            index.finishWrite(stream)
        } catch(error:Exception) {
            index.failWrite(stream)
            throw error
        }
    }

    /** Copy while the picker grant is valid, so runs need no persistent URI permissions. */
    fun import(context:Context,uris:List<Uri>,existing:List<GlinerLabSample>):ImportResult {
        val dir=File(context.noBackupFilesDir,FOLDER)
        check(dir.exists() || dir.mkdirs()) { "Cannot create private image folder" }
        val corpus=JSONObject(context.assets.open("${GlinerLabCorpus.ASSET_ROOT}/inputs.json").bufferedReader().use { it.readText() })
            .getJSONArray("images").let { array -> (0 until array.length()).map { array.getJSONObject(it).optString("sha256") }.toSet() }
        val samples=existing.toMutableList()
        val created=mutableListOf<File>()
        val errors=mutableListOf<String>()
        var duplicates=0
        try {
            uris.forEach { uri ->
                val name=runCatching {
                    context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { cursor ->
                        if(cursor.moveToFirst()) cursor.getString(0) else null
                    }
                }.getOrNull().orEmpty().ifBlank { "Selected image" }.filterNot(Char::isISOControl).take(120).ifBlank { "Selected image" }
                val temp=File.createTempFile("import-",".tmp",dir)
                try {
                    val digest=MessageDigest.getInstance("SHA-256")
                    var count=0L
                    val input=context.contentResolver.openInputStream(uri) ?: error("Cannot read selected file")
                    input.use { source -> temp.outputStream().use { output ->
                        val buffer=ByteArray(65_536)
                        while(true) {
                            val read=source.read(buffer)
                            if(read<0) break
                            count+=read
                            require(count<=MAX_BYTES) { "Image exceeds 64 MB" }
                            digest.update(buffer,0,read)
                            output.write(buffer,0,read)
                        }
                    } }
                    val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
                    BitmapFactory.decodeFile(temp.absolutePath,bounds)
                    require(bounds.outWidth>0 && bounds.outHeight>0 && bounds.outWidth.toLong()*bounds.outHeight<=MAX_PIXELS) {
                        "Unsupported, invalid, or oversized image"
                    }
                    val hash=digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                    if(samples.any { it.sha256==hash }) { duplicates++; return@forEach }
                    val id="custom-$hash.img"
                    val destination=file(context,id)
                    check(!destination.exists()) { "A private copy already exists without an index entry" }
                    check(temp.renameTo(destination)) { "Cannot save private copy" }
                    created+=destination
                    samples+=GlinerLabSample(id,0,name,hash,true,emptyMap(),isCustom=true,matchesCorpus=hash in corpus)
                } catch(error:Exception) {
                    errors += "$name: ${error.message ?: error.javaClass.simpleName}"
                } finally { temp.delete() }
            }
            save(context,samples)
        } catch(error:Exception) {
            created.forEach { it.delete() }
            throw error
        }
        return ImportResult(samples,created.size,duplicates,errors)
    }

    /** Reset a corrupt private index after explicit confirmation; originals are never touched. */
    fun reset(context:Context) {
        val dir=File(context.noBackupFilesDir,FOLDER)
        dir.listFiles().orEmpty().filter { it.isFile }.forEach { check(it.delete()) { "Cannot remove ${it.name}" } }
    }

    /** Delete only copies owned by this feature, never the original picker documents. */
    fun remove(context:Context,samples:List<GlinerLabSample>,ids:Set<String>) {
        save(context,samples.filterNot { it.image in ids })
        samples.filter { it.image in ids && it.isCustom }.forEach { file(context,it.image).delete() }
    }
}
