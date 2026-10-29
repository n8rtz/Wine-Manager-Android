package com.yourapp.wine.phonecorpus

import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Lossless normalization of suite export rows. IDs are archive-local; original strings are preserved. */
internal object LabSuiteArchive {
    private val runKeys=setOf("recent_rank","run_id","date","time","build_number","build_commit","lab_modules","corpus_key")
    private val imageKeys=setOf("image","image_sha256")
    private val commonKeys=setOf("candidate","post_processing_version","spatial_source_count","spatial_reading_count",
        "spatial_filter_dropped","spatial_merged_groups")
    private val candidateKeys=setOf("phrase_id","candidate_values","source_observations","base_rank")
    private val scoreColumns=listOf("row_type","r","i","review_number","source_filename","ocr_ref","field","recognized","expected","reference_status","outcome",
        "execution_status","correct","incorrect","attempted","eligible","complete","failed","total_correct")

    private fun hash(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun canonical(value:Any?):Any = when(value) {
        is JSONObject -> JSONObject().also { out -> value.keys().asSequence().toList().sorted().forEach { out.put(it,canonical(value.get(it))) } }
        is JSONArray -> JSONArray().also { out -> for(i in 0 until value.length()) out.put(canonical(value.get(i))) }
        null -> JSONObject.NULL
        else -> value
    }
    private fun csv(value:String)="\""+value.replace("\"","\"\"")+"\""

    fun write(target:File,rows:Sequence<Map<String,String>>) {
        val work=File(target.parentFile,"archive-${java.util.UUID.randomUUID()}").apply { check(mkdirs()) }
        val writers=linkedMapOf<String,java.io.BufferedWriter>()
        val counts=linkedMapOf<String,Long>()
        val ids=mutableMapOf<String,MutableMap<String,Int>>()
        fun writer(name:String)=writers.getOrPut(name) { File(work,name).bufferedWriter(Charsets.UTF_8) }
        fun line(name:String,value:JSONObject) {
            writer(name).apply { write(value.toString());newLine() }
            counts[name]=(counts[name] ?: 0)+1
        }
        fun intern(name:String,value:JSONObject):Int {
            val text=canonical(value).toString()
            val table=ids.getOrPut(name) { mutableMapOf() }
            val key=hash(text.toByteArray(Charsets.UTF_8))
            return table[key] ?: table.size.plus(1).also { id ->
                table[key]=id
                line(name,JSONObject().put("id",id).put("v",value))
            }
        }
        try {
            val scores=writer("scores.csv")
            scores.write(scoreColumns.joinToString(",",transform=::csv));scores.newLine()
            for(row in rows) {
                val payload=JSONObject(row)
                val run=JSONObject()
                runKeys.forEach { key -> if(payload.has(key)) run.put(key,payload.remove(key)) }
                val image=JSONObject()
                imageKeys.forEach { key -> if(payload.has(key)) image.put(key,payload.remove(key)) }
                val r=if(run.length()>0) intern("runs.jsonl",run) else 0
                val i=if(image.length()>0) intern("images.jsonl",image) else 0
                val event=JSONObject().put("r",r).put("i",i)
                // JSON becomes native data, not a JSON string escaped a second time inside CSV.
                val encoded=payload.optString("diagnostic_json")
                if(encoded.isNotEmpty()) {
                    val diagnostic=JSONObject(encoded)
                    payload.remove("diagnostic_json")
                    val common=JSONObject()
                    commonKeys.forEach { key -> if(diagnostic.has(key)) common.put(key,diagnostic.remove(key)) }
                    if(common.length()>0) event.put("m",intern("common.jsonl",common))
                    val candidate=JSONObject()
                    candidateKeys.forEach { key -> if(diagnostic.has(key)) candidate.put(key,diagnostic.remove(key)) }
                    if(candidate.length()>0) event.put("c",intern("candidates.jsonl",candidate))
                    // Volatile provenance is retained on occurrences; it must not prevent content deduplication.
                    val occurrence=JSONObject()
                    listOf("sequence","event_at_ms").forEach { key -> if(diagnostic.has(key)) occurrence.put(key,diagnostic.remove(key)) }
                    if(occurrence.length()>0) event.put("o",occurrence)
                    event.put("d",intern("objects.jsonl",diagnostic))
                }
                event.put("p",intern("objects.jsonl",payload))
                line("events.jsonl",event)
                val type=row["row_type"]
                if(type in setOf("run_summary","parameter_summary","wine_field")) {
                    scores.write(scoreColumns.joinToString(",") { key -> csv(when(key) {
                        "r" -> r.toString(); "i" -> i.toString(); else -> row[key].orEmpty()
                    }) });scores.newLine()
                    counts["scores.csv"]=(counts["scores.csv"] ?: 0)+1
                }
            }
            writers.values.forEach { it.close() };writers.clear()
            val entries=JSONArray()
            work.listFiles().orEmpty().sortedBy { it.name }.forEach { file ->
                val digest=MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input -> val buffer=ByteArray(65536);while(true) {
                    val n=input.read(buffer);if(n<0) break;digest.update(buffer,0,n)
                } }
                entries.put(JSONObject().put("name",file.name).put("bytes",file.length())
                    .put("records",counts[file.name] ?: 0).put("sha256",digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }))
            }
            File(work,"manifest.json").writeText(JSONObject().put("format","wine-lab-suite").put("version",1)
                .put("files",entries).put("dictionary_counts",JSONObject(ids.mapValues { it.value.size })).toString(2))
            File(work,"README.txt").writeText("""
                Wine Lab suite archive v1. No images. Shared OCR, candidates and identical payloads are deduplicated.
                scores.csv is a convenience view; runs.jsonl and images.jsonl resolve its short r/i identifiers.
                events.jsonl preserves every source export row in order. Dictionary files contain {id,v} records.
                Reconstruct a row by merging runs[r], images[i], objects[p]. If d is present, merge objects[d],
                common[m], candidates[c] and occurrence o; serialize this as diagnostic_json. Zero IDs mean absent.
                Nothing is sampled, truncated or discarded by archive normalization. Upstream trace limits remain explicit.
                Use tools/analyze-lab-runs.py directly on this ZIP, or tools/lab_suite_archive.py to reconstruct CSV.
                manifest.json records uncompressed byte counts, record counts and SHA-256 integrity hashes.
            """.trimIndent())
            ZipOutputStream(target.outputStream().buffered()).use { zip ->
                zip.setLevel(9)
                work.listFiles().orEmpty().sortedBy { it.name }.forEach { file ->
                    zip.putNextEntry(ZipEntry(file.name).apply { time=0L })
                    file.inputStream().use { it.copyTo(zip) };zip.closeEntry()
                }
            }
        } catch(error:Throwable) {
            target.delete();throw error
        } finally {
            writers.values.forEach { runCatching { it.close() } }
            work.deleteRecursively()
        }
    }
}
