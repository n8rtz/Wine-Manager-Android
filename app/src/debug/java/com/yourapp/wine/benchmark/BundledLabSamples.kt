package com.yourapp.wine.benchmark

import android.content.Context
import androidx.core.content.FileProvider
import com.yourapp.wine.capture.copyLabelBytes
import com.yourapp.wine.fill.FieldId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Debug-only image fixtures and scoring truth. Nothing from this registry enters recognition. */
internal object BundledLabSamples {
    private const val PREFS="ocr-lab-bundled-review-v1"
    suspend fun load(context:Context):List<LabLabel> = withContext(Dispatchers.IO) {
        val manifest=context.assets.open("ocr-lab-v1/samples.json").bufferedReader().use { JSONObject(it.readText()) }
        require(manifest.getInt("version")==1)
        val entries=manifest.getJSONArray("samples")
        require(entries.length() in 1..10)
        val folder=File(context.cacheDir,"ocr-lab-bundled-v1").apply { check(isDirectory || mkdirs()) }
        val owner=currentCoroutineContext()
        List(entries.length()) { index ->
            owner.ensureActive()
            val sample=entries.getJSONObject(index)
            val sha=sample.getString("sha256")
            require(sha.matches(Regex("[a-f0-9]{64}")))
            val asset=sample.getString("asset")
            require(asset=="$sha.jpg")
            val target=File(folder,asset)
            val temporary=File.createTempFile("sample-",".jpg",folder)
            try {
                context.assets.open("ocr-lab-v1/$asset").use { input -> temporary.outputStream().use { output ->
                    copyLabelBytes(input,output) { owner.ensureActive() }
                } }
                val digest=MessageDigest.getInstance("SHA-256")
                temporary.inputStream().use { input ->
                    val buffer=ByteArray(32768)
                    while(true) { owner.ensureActive();val n=input.read(buffer);if(n<0) break;digest.update(buffer,0,n) }
                }
                check(digest.digest().joinToString("") { "%02x".format(it) }==sha) { "Bundled photo checksum mismatch" }
                // Same-directory rename replaces a prior cache copy atomically on Android.
                check(temporary.renameTo(target))
            } finally { temporary.delete() }
            val saved=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString(sha,null)?.let {
                runCatching { JSONObject(it) }.getOrNull()?.takeIf { stored ->
                    val fields=stored.optJSONObject("expected")
                    fields!=null && FieldId.entries.all { field -> fields.has(field.name) } && stored.has("label")
                }
            }
            val truth=saved?.optJSONObject("expected") ?: sample.getJSONObject("expected")
            LabLabel(uri=FileProvider.getUriForFile(context,"${context.packageName}.provider",target),
                label=saved?.optString("label",sample.getString("label")) ?: sample.getString("label"),
                expected=FieldId.entries.associateWith { truth.optString(it.name,"") },
                reviewed=saved?.optBoolean("reviewed",false) ?: false,presetKey=sha)
        }
    }

    /** Only explicit user edits/review confirmations persist; ordinary photo queues are unaffected. */
    fun save(context:Context,labels:List<LabLabel>) {
        val presets=labels.filter { it.presetKey!=null }
        if(presets.isEmpty()) return
        val editor=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit()
        presets.forEach { label ->
            val fields=JSONObject()
            FieldId.entries.forEach { fields.put(it.name,label.expected[it].orEmpty()) }
            editor.putString(requireNotNull(label.presetKey),JSONObject().put("label",label.label)
                .put("expected",fields).put("reviewed",label.reviewed).toString())
        }
        editor.apply()
    }
}
