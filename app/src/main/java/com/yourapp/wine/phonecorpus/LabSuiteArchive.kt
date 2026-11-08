package com.yourapp.wine.phonecorpus

import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.security.MessageDigest

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

    fun write(target:File,rows:Sequence<Map<String,String>>):List<File> {
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
            return writeJsonParts(target,work,ids.mapValues { it.value.size },counts)
        } catch(error:Throwable) {
            target.delete();throw error
        } finally {
            writers.values.forEach { runCatching { it.close() } }
            work.deleteRecursively()
        }
    }
    /** Plain JSON, never encoded binary. All parts plus the manifest form one diagnostic package. */
    private fun writeJsonParts(target:File,work:File,dictionaryCounts:Map<String,Int>,recordCounts:Map<String,Long>):List<File> {
        val packageId=java.util.UUID.randomUUID().toString()
        val tables=listOf("runs.jsonl","images.jsonl","candidates.jsonl","common.jsonl","objects.jsonl","events.jsonl")
        val outputs=mutableListOf<File>()
        val maxBytes=20_000_000L
        var stream:java.io.OutputStream?=null
        var size=0L
        var first=true
        fun finish() { stream?.apply { write("\n]}".toByteArray());close() };stream=null }
        fun next() {
            finish()
            val file=File(target.parentFile,"${target.nameWithoutExtension}-part-${outputs.size+1}.json")
            outputs+=file
            stream=file.outputStream().buffered()
            val header="{\"format\":\"wine-lab-suite-json\",\"version\":1,\"package_id\":\"$packageId\",\"part\":${outputs.size},\"records\":[\n"
            val bytes=header.toByteArray(Charsets.UTF_8);stream!!.write(bytes);size=bytes.size.toLong();first=true
        }
        try {
            next()
            tables.forEachIndexed { code,name ->
                val file=File(work,name)
                if(file.isFile) file.bufferedReader(Charsets.UTF_8).useLines { lines -> lines.forEach { line ->
                    val record="[$code,$line]".toByteArray(Charsets.UTF_8)
                    check(record.size+512<maxBytes) { "One diagnostic record exceeds the JSON part limit; nothing was truncated" }
                    if(size+record.size+4>maxBytes) next()
                    if(!first) { stream!!.write(",\n".toByteArray());size+=2 }
                    stream!!.write(record);size+=record.size;first=false
                } }
            }
            finish()
            val scores=File(target.parentFile,"${target.nameWithoutExtension}-scores.csv")
            File(work,"scores.csv").copyTo(scores,overwrite=true)
            val files=JSONArray()
            (outputs+scores).forEach { file ->
                val digest=MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input -> val buffer=ByteArray(65536);while(true) {
                    val n=input.read(buffer);if(n<0) break;digest.update(buffer,0,n)
                } }
                files.put(JSONObject().put("name",file.name).put("bytes",file.length())
                    .put("sha256",digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }))
            }
            val manifest=File(target.parentFile,"${target.nameWithoutExtension}-manifest.json")
            manifest.writeText(JSONObject().put("format","wine-lab-suite-json-manifest").put("version",1)
                .put("package_id",packageId).put("parts",outputs.size).put("tables",JSONArray(tables))
                .put("dictionary_counts",JSONObject(dictionaryCounts)).put("record_counts",JSONObject(recordCounts)).put("files",files)
                .put("instructions","Upload all numbered JSON parts and this manifest. Scores CSV is optional. Short IDs resolve across parts; no binary encoding or images.").toString(2))
            return listOf(manifest,scores)+outputs
        } catch(error:Throwable) {
            runCatching { finish() };outputs.forEach { it.delete() };throw error
        }
    }

}
