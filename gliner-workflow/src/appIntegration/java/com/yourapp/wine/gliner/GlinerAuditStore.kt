package com.yourapp.wine.gliner

import android.content.Context
import android.util.AtomicFile
import com.yourapp.wine.phonecorpus.PhoneCorpusMath
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Private, user-confirmed references; never consulted by the inference pipeline. */
internal data class GlinerAuditReference(
    val image:String, val sha256:String, val name:String,
    val targets:Map<String,PhoneCorpusMath.Target>,
    val warnings:List<String> = emptyList(),
)

internal data class GlinerAuditRow(
    val reference:GlinerAuditReference,
    val status:String,
    val message:String,
    val fields:Map<String,String>,
    val outcomes:Map<String,String>,
    val exactCorpusMatch:Boolean,
    val diagnostics:JSONObject,
)

internal object GlinerAuditStore {
    const val BUNDLED_ASSET="gliner-personal-audit-v1/references.json"
    private val hashPattern=Regex("[0-9a-f]{64}")
    private const val MAX_IMPORT_BYTES=5_000_000
    private fun folder(context:Context)=File(context.noBackupFilesDir,"gliner-audit")
    private fun references(context:Context)=AtomicFile(File(folder(context),"references.json"))

    fun target(status:String,value:String,aliases:List<String> = emptyList()):PhoneCorpusMath.Target {
        require(status in setOf("expected_value","expected_blank","unresolved")) { "Invalid reference status" }
        require(status!="expected_value" || PhoneCorpusMath.normalize(value).isNotEmpty()) { "An expected value is empty" }
        val accepted=if(status=="expected_value") (listOf(value)+aliases).map(PhoneCorpusMath::normalize).filter(String::isNotEmpty).toSet() else emptySet()
        return PhoneCorpusMath.Target(status,accepted,if(status=="expected_value") value else "")
    }

    fun parse(json:JSONObject):List<GlinerAuditReference> {
        val rows=json.optJSONArray("audit_references") ?: json.optJSONArray("custom_images")
            ?: error("This is not a GLiNER reviewed-image JSON export")
        require(rows.length() in 1..500) { "The export must contain 1–500 images" }
        val seen=mutableSetOf<String>()
        return (0 until rows.length()).map { index ->
            val row=rows.getJSONObject(index)
            val hash=row.getString("sha256").lowercase()
            require(hashPattern.matches(hash)) { "Invalid image SHA-256 at row ${index+1}" }
            val image="custom-$hash.img"
            require(row.optString("image",image)==image && seen.add(image)) { "Duplicate or mismatched image ID at row ${index+1}" }
            val review=row.optJSONObject("manual_review") ?: row.optJSONObject("references")
                ?: error("Missing review for image ${index+1}")
            val targets=PhoneCorpusMath.fields.associateWith { field ->
                val entry=review.optJSONObject(field)
                if(entry==null) target("unresolved","") else {
                    val status=entry.optString("expected_status","unresolved")
                    val value=if(status=="expected_value") entry.getString("expected") else ""
                    val aliases=entry.optJSONArray("accepted")?.let { array -> (0 until array.length()).map { array.getString(it) } }.orEmpty()
                    target(status,value,aliases)
                }
            }
            val savedWarnings=row.optJSONArray("warnings")
            val warnings=if(savedWarnings!=null) (0 until savedWarnings.length()).map { savedWarnings.getString(it) }
                else PhoneCorpusMath.fields.mapNotNull { field ->
                    val entry=review.optJSONObject(field)
                    val predicted=row.optJSONObject("fields")?.optString(field).orEmpty()
                    if(entry?.optString("verdict")=="incorrect" && entry.optString("expected_status")=="expected_blank" && predicted.isBlank())
                        "$field: marked incorrect, but both prediction and correction are blank" else null
                }
            GlinerAuditReference(image,hash,row.optString("source_filename",image).take(120),targets,warnings)
        }
    }

    /** Small built-in reference set; photos and personal OCR are not bundled. */
    fun readBundledDocument(context:Context):JSONObject = JSONObject(
        context.assets.open(BUNDLED_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
    )

    fun readDocument(context:Context,uri:android.net.Uri):JSONObject {
        val bytes=context.contentResolver.openInputStream(uri)?.use { input ->
            val out=java.io.ByteArrayOutputStream()
            val buffer=ByteArray(65536)
            while(true) {
                val n=input.read(buffer)
                if(n<0) break
                require(out.size()+n<=MAX_IMPORT_BYTES) { "JSON exceeds 5 MB" }
                out.write(buffer,0,n)
            }
            out.toByteArray()
        } ?: error("Cannot open selected JSON")
        return JSONObject(bytes.toString(Charsets.UTF_8))
    }

    fun referenceJson(row:GlinerAuditReference):JSONObject = JSONObject().apply {
        put("image",row.image); put("sha256",row.sha256); put("source_filename",row.name)
        put("warnings",JSONArray(row.warnings))
        put("references",JSONObject().apply { PhoneCorpusMath.fields.forEach { field ->
            val target=row.targets.getValue(field)
            put(field,JSONObject().put("expected_status",target.status).apply {
                if(target.status!="unresolved") put("expected",target.display)
                if(target.status=="expected_value") put("accepted",JSONArray(target.accepted.sorted()))
            })
        } })
    }

    private fun writeAtomic(file:AtomicFile,json:JSONObject) {
        val dir=file.baseFile.parentFile ?: error("Missing audit folder")
        check(dir.exists() || dir.mkdirs()) { "Cannot create audit folder" }
        val stream=file.startWrite()
        try { stream.write(json.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch(error:Exception) { file.failWrite(stream); throw error }
    }

    fun saveReferences(context:Context,rows:List<GlinerAuditReference>) {
        require(rows.size in 1..500 && rows.map { it.image }.toSet().size==rows.size) { "The audit must contain 1–500 distinct photos" }
        writeAtomic(references(context),JSONObject().put("schema",1).put("audit_references",JSONArray(rows.map(::referenceJson))))
    }

    fun loadReferences(context:Context):List<GlinerAuditReference> {
        val file=references(context)
        if(!file.baseFile.exists() && !File(file.baseFile.path+".bak").exists()) return emptyList()
        return parse(JSONObject(file.openRead().bufferedReader().use { it.readText() }))
    }

    /** Each run is independent; overwrite only its own checkpoint as each image finishes. */
    fun saveRun(context:Context,id:String,payload:JSONObject) {
        require(id.matches(Regex("[0-9]+-[a-f0-9-]+")))
        writeAtomic(AtomicFile(File(folder(context),"run-$id.json")),payload)
        writeAtomic(AtomicFile(File(folder(context),"latest.json")),payload)
    }

    fun loadLatest(context:Context):JSONObject? {
        val file=AtomicFile(File(folder(context),"latest.json"))
        if(!file.baseFile.exists() && !File(file.baseFile.path+".bak").exists()) return null
        return JSONObject(file.openRead().bufferedReader().use { it.readText() })
    }

    fun history(context:Context):List<JSONObject> = folder(context).listFiles().orEmpty()
        .filter { it.isFile && it.name.matches(Regex("run-[0-9]+-[a-f0-9-]+\\.json")) }
        .sortedBy { it.name }.map { JSONObject(it.readText()) }

    fun compare(reference:GlinerAuditReference,fields:Map<String,String>,failed:Boolean):Map<String,String> =
        PhoneCorpusMath.fields.associateWith { field ->
            PhoneCorpusMath.outcome(reference.targets.getValue(field),fields[field].orEmpty(),failed)
        }
}
