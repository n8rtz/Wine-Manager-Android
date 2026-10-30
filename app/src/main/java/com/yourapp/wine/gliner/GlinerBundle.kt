package com.yourapp.wine.gliner

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

/** One expected file of the GLiNER-Small INT4 bundle. `sha256`/`bytes` are null until pinned. */
internal data class GlinerBundleFile(
    val name:String,
    val role:String,
    val bytes:Long?,
    val sha256:String?,
    val url:String?,
)

internal data class GlinerFileState(
    val name:String,
    val role:String,
    val present:Boolean,
    val source:String,
    val bytes:Long,
    val expectedBytes:Long?,
    val sha256:String,
    val pinned:Boolean,
    val matchesPin:Boolean,
    val issue:String,
)

/**
 * Result of resolving the GLiNER bundle. Nothing here downloads anything: the lab never reaches
 * the network at run time. Files are either shipped in `assets/gliner-lab-v1` by the opt-in
 * Gradle task, or imported by the user from storage into app-private storage.
 */
internal data class GlinerBundleStatus(
    val files:List<GlinerFileState>,
    val model:File?,
    val tokenizer:File?,
    val config:File?,
    val int4:Boolean,
    val notes:List<String>,
) {
    private val blocking:List<GlinerFileState> get() = files.filter { !it.present || (it.pinned && !it.matchesPin) }
    val ready:Boolean get() = blocking.isEmpty() && model!=null && tokenizer!=null
    val missing:List<String> get() = blocking.map { it.name }
    fun file(name:String):GlinerFileState? = files.firstOrNull { it.name==name }
    fun summary():String = when {
        ready -> "GLiNER-Small bundle ready"+(if(int4) " · INT4 MatMulNBits" else " · not INT4")
        missing.isNotEmpty() -> "Model bundle missing: ${missing.joinToString(", ")}"
        else -> "Model bundle incomplete"
    }
}

/**
 * Bundle manifest + private-storage resolver for the GLiNER lane model.
 *
 * Layout (all inside the private `gliner-lab-v1` folder, or the matching asset folder):
 *  - `gliner_small_int4.onnx`  INT4 weight-only quantized GLiNER-Small export (MatMulNBits)
 *  - `tokenizer.json`          Hugging Face fast-tokenizer file from the same export
 *  - `gliner_config.json`      `ent_token` / `sep_token` / `max_width` / `max_len`
 *
 * The manifest is the single source of truth for names, roles, sizes and SHA-256 pins, so a
 * replaced or truncated model is reported instead of being loaded.
 */
internal object GlinerBundle {
    const val ASSET_ROOT="gliner-lab-v1"
    const val PRIVATE_FOLDER="gliner-lab-v1"
    const val MANIFEST="model-manifest.json"
    const val MODEL_FILE="gliner_small_int4.onnx"
    const val TOKENIZER_FILE="tokenizer.json"
    const val CONFIG_FILE="gliner_config.json"

    private const val PREFS="gliner-lab-bundle-v1"
    private const val HASH_PREFIX="sha:"
    private const val INT4_PREFIX="int4:"

    private val defaults=listOf(
        GlinerBundleFile(MODEL_FILE,"model",null,null,null),
        GlinerBundleFile(TOKENIZER_FILE,"tokenizer",null,null,null),
        GlinerBundleFile(CONFIG_FILE,"config",null,null,null),
    )

    fun manifest(context:Context):List<GlinerBundleFile> {
        val text=runCatching { context.assets.open("$ASSET_ROOT/$MANIFEST").bufferedReader().use { it.readText() } }.getOrNull()
            ?: return defaults
        return runCatching {
            val root=JSONObject(text)
            val list=root.optJSONArray("files") ?: root.optJSONObject("model")?.optJSONArray("files") ?: return defaults
            val parsed=(0 until list.length()).mapNotNull { index ->
                val entry=list.optJSONObject(index) ?: return@mapNotNull null
                val name=entry.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                GlinerBundleFile(name,entry.optString("role","model"),
                    entry.optLong("bytes",-1L).takeIf { it>0 },
                    entry.optString("sha256").takeIf { it.length==64 },
                    entry.optString("url").takeIf { it.startsWith("http") })
            }
            if(parsed.isEmpty()) defaults else parsed
        }.getOrDefault(defaults)
    }

    fun privateDir(context:Context):File =
        File(context.noBackupFilesDir,PRIVATE_FOLDER).apply { check(isDirectory || mkdirs()) { "Cannot create the GLiNER model folder" } }

    fun status(context:Context):GlinerBundleStatus {
        val prefs=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
        val states=manifest(context).map { entry -> resolve(context,prefs,entry) }
        val model=states.firstOrNull { it.name==MODEL_FILE && it.present && (!it.pinned || it.matchesPin) }
            ?.let { File(privateDir(context),it.name) }
        val tokenizer=states.firstOrNull { it.name==TOKENIZER_FILE && it.present }?.let { File(privateDir(context),it.name) }
        val config=states.firstOrNull { it.name==CONFIG_FILE && it.present }?.let { File(privateDir(context),it.name) }
        val int4=model!=null && isInt4(prefs,model)
        val notes=mutableListOf<String>()
        states.filter { it.pinned && it.present && !it.matchesPin }.forEach { notes += "${it.name}: ${it.issue}" }
        if(states.any { it.present && !it.pinned }) notes += "Bundle is not pinned yet: only file shape is verified, not the bytes"
        if(model!=null && !int4) notes += "Model has no MatMulNBits node: this is not the INT4 build"
        if(config==null) notes += "gliner_config.json is optional only when the export writes ent/sep tokens elsewhere"
        return GlinerBundleStatus(states,model,tokenizer,config,int4,notes)
    }

    /**
     * Private copy wins; otherwise the bundled asset is copied once into private storage (ONNX
     * Runtime needs a readable file it can memory-map, and assets are compressed inside the APK).
     */
    private fun resolve(context:Context,prefs:android.content.SharedPreferences,entry:GlinerBundleFile):GlinerFileState {
        val target=File(privateDir(context),entry.name)
        if(target.isFile) return state(prefs,entry,target,"imported")
        val assetAvailable=runCatching { context.assets.open("$ASSET_ROOT/${entry.name}").close();true }.getOrDefault(false)
        if(!assetAvailable) {
            return GlinerFileState(entry.name,entry.role,false,"absent",0L,entry.bytes,"",entry.sha256!=null,false,
                if(entry.sha256==null) "Not provisioned yet" else "Missing")
        }
        val copied=runCatching {
            val temporary=File(privateDir(context),"${entry.name}.${UUID.randomUUID()}.part")
            try {
                context.assets.open("$ASSET_ROOT/${entry.name}").use { input ->
                    FileOutputStream(temporary).use { output -> input.copyTo(output,65536) }
                }
                check(temporary.renameTo(target)) { "Cannot install bundled $entry.name" }
                true
            } finally { temporary.delete() }
        }.getOrDefault(false)
        if(!copied) {
            return GlinerFileState(entry.name,entry.role,false,"asset",0L,entry.bytes,"",entry.sha256!=null,false,
                "Bundled asset could not be copied into private storage")
        }
        return state(prefs,entry,target,"asset")
    }

    private fun state(prefs:android.content.SharedPreferences,entry:GlinerBundleFile,file:File,source:String):GlinerFileState {
        val sha=sha256(prefs,file)
        val matches=entry.sha256==null || entry.sha256==sha
        val sizeMatches=entry.bytes==null || entry.bytes==file.length()
        val issue=when {
            !matches -> "SHA-256 mismatch"
            !sizeMatches -> "Size ${file.length()} does not match the pinned ${entry.bytes}"
            else -> ""
        }
        return GlinerFileState(entry.name,entry.role,true,source,file.length(),entry.bytes,sha,entry.sha256!=null,
            matches && sizeMatches,issue)
    }

    fun sha256(prefs:android.content.SharedPreferences,file:File):String {
        val key="$HASH_PREFIX${file.absolutePath}:${file.length()}:${file.lastModified()}"
        prefs.getString(key,null)?.let { return it }
        val digest=MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer=ByteArray(65536)
            while(true) { val read=input.read(buffer);if(read<0) break;digest.update(buffer,0,read) }
        }
        val value=digest.digest().joinToString("") { "%02x".format(it) }
        prefs.edit().putString(key,value).apply()
        return value
    }

    /**
     * INT4 weight-only quantization replaces MatMul/Gather weights with `MatMulNBits` nodes. The
     * op name is an ASCII string in the graph protobuf, so a streaming scan is a cheap, reliable
     * "is this really the INT4 file" check that does not require loading the graph first.
     */
    fun isInt4(prefs:android.content.SharedPreferences,file:File):Boolean {
        val key="$INT4_PREFIX${file.absolutePath}:${file.length()}:${file.lastModified()}"
        prefs.getString(key,null)?.let { return it=="true" }
        val needle="MatMulNBits".toByteArray(Charsets.US_ASCII)
        var carry=ByteArray(0)
        var found=false
        file.inputStream().use { input ->
            val buffer=ByteArray(1 shl 20)
            while(!found) {
                val read=input.read(buffer)
                if(read<0) break
                val window=ByteArray(carry.size+read)
                System.arraycopy(carry,0,window,0,carry.size)
                System.arraycopy(buffer,0,window,carry.size,read)
                if(indexOf(window,needle)>=0) found=true
                else carry=window.copyOfRange(maxOf(0,window.size-needle.size),window.size)
            }
        }
        prefs.edit().putString(key,found.toString()).apply()
        return found
    }

    private fun indexOf(haystack:ByteArray,needle:ByteArray):Int {
        if(needle.isEmpty() || haystack.size<needle.size) return -1
        outer@ for(start in 0..haystack.size-needle.size) {
            for(offset in needle.indices) if(haystack[start+offset]!=needle[offset]) continue@outer
            return start
        }
        return -1
    }

    data class ImportOutcome(val imported:List<String>,val rejected:List<String>,val message:String)

    /**
     * Copies user-selected bundle files into private storage. The picker may return any file; only
     * the three declared names are accepted, and a JSON/ONNX sanity check runs before the rename so
     * a renamed screenshot cannot become "the model".
     */
    fun import(context:Context,uris:List<Uri>):ImportOutcome {
        val allowed=manifest(context).map { it.name }.toSet()
        val imported=mutableListOf<String>()
        val rejected=mutableListOf<String>()
        uris.forEach { uri ->
            val name=displayName(context,uri)
            if(name==null || name !in allowed) { rejected += name ?: uri.toString(); return@forEach }
            val temporary=File(privateDir(context),"$name.${UUID.randomUUID()}.part")
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(temporary).use { output -> input.copyTo(output,65536) }
                } ?: error("Cannot read the selected file")
                val problem=validate(name,temporary)
                if(problem!=null) { rejected += "$name ($problem)";return@forEach }
                val target=File(privateDir(context),name)
                check(temporary.renameTo(target) || runCatching { temporary.copyTo(target,overwrite=true);true }.getOrDefault(false)) {
                    "Cannot install $name"
                }
                imported += name
            } catch(cancel:java.io.IOException) {
                rejected += "$name (${cancel.javaClass.simpleName})"
            } finally { temporary.delete() }
        }
        val message=when {
            imported.isNotEmpty() && rejected.isEmpty() -> "Installed ${imported.joinToString(", ")}"
            imported.isNotEmpty() -> "Installed ${imported.joinToString(", ")}; ignored ${rejected.joinToString(", ")}"
            rejected.isEmpty() -> "No files selected"
            else -> "Nothing installed: ${rejected.joinToString(", ")}"
        }
        return ImportOutcome(imported,rejected,message)
    }

    fun clear(context:Context) {
        privateDir(context).listFiles()?.forEach { it.delete() }
    }

    private fun displayName(context:Context,uri:Uri):String? = runCatching {
        context.contentResolver.query(uri,null,null,null,null)?.use { cursor ->
            val index=cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if(index>=0 && cursor.moveToFirst()) cursor.getString(index) else null
        } ?: uri.lastPathSegment?.substringAfterLast('/')
    }.getOrNull()

    private fun validate(name:String,file:File):String? {
        if(!file.isFile || file.length()<16) return "empty file"
        return when(name) {
            MODEL_FILE -> {
                if(file.length()<1_000_000L) "too small to be the ONNX export"
                else if(String(file.inputStream().use { it.readNBytes(512) },Charsets.ISO_8859_1).trimStart().startsWith("<")) "looks like an HTML page, not ONNX"
                else null
            }
            TOKENIZER_FILE -> runCatching { GlinerTokenizer.parse(file.readText()) }.fold({ null },{ "${it.javaClass.simpleName}" })
            CONFIG_FILE -> runCatching { GlinerConfig.parse(file.readText()) }.fold({ null },{ "${it.javaClass.simpleName}" })
            else -> "not part of the bundle"
        }
    }

    /** Files the screen offers for import, in the order the instructions list them. */
    fun expectedNames(context:Context):List<String> = manifest(context).map { it.name }

    fun describeManifest(context:Context):String = buildString {
        appendLine("Bundle folder: assets/$ASSET_ROOT or private $PRIVATE_FOLDER")
        manifest(context).forEach { entry ->
            appendLine("${entry.role}: ${entry.name} · "+(entry.bytes?.let { "$it bytes" } ?: "size not pinned")+
                " · "+(entry.sha256?.let { "sha256 ${it.take(12)}…" } ?: "sha256 not pinned"))
        }
    }

    fun declaredFiles(context:Context):JSONArray = JSONArray().apply {
        manifest(context).forEach { entry ->
            put(JSONObject().put("name",entry.name).put("role",entry.role)
                .put("bytes",entry.bytes ?: JSONObject.NULL).put("sha256",entry.sha256 ?: JSONObject.NULL))
        }
    }
}
