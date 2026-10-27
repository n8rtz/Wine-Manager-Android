package com.yourapp.wine.kb

import android.content.Context
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.yourapp.wine.merge.TextNorm
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Read-only lookup over a packaged producer catalog. Call only from a worker thread. */
class ProducerCatalogDatabaseLookup private constructor(
    private val connection: SQLiteConnection,
    val totalProducers: Int,
    val catalog: Catalog
) : AutoCloseable {
    /** Packaged producer catalogs inspectable on the Database Lookup page.
     * Owner rule (2026-10-26, .agents/instructions.md #17): every future packaged
     * database addition/change must be wired into that page in the same change. */
    enum class Catalog(val assetDir:String,val databaseName:String) {
        PRIMARY("producer-gazetteer-v1","producers.sqlite"),
        EXPANDED("producer-gazetteer-expanded-v1","producers-expanded.sqlite")
    }
    data class Country(val name:String,val producerCount:Int)
    data class Source(val id:String,val producerCount:Int)
    private val trackSources:Boolean get()=catalog==Catalog.EXPANDED

    fun countries():List<Country> {
        val rows=mutableListOf<Country>()
        connection.prepare("SELECT country_group,COUNT(*) FROM producers GROUP BY country_group ORDER BY country_group").use { s ->
            while(s.step()) rows += Country(s.getText(0),s.getLong(1).toInt())
        }
        return rows
    }

    /** Per-source record counts; only the expanded catalog tracks sources. Untagged rows are the LWIN subset. */
    fun sources():List<Source> {
        if(!trackSources) return emptyList()
        val rows=mutableListOf<Source>()
        connection.prepare("SELECT COALESCE(s.source,'lwin'),COUNT(*) FROM producers p LEFT JOIN producer_sources s ON s.id=p.id GROUP BY COALESCE(s.source,'lwin') ORDER BY 1").use { s ->
            while(s.step()) rows += Source(s.getText(0),s.getLong(1).toInt())
        }
        return rows
    }

    /** Display names for producer ids (wine-name result context); unknown ids are omitted. */
    fun producerNames(ids: Collection<String>): Map<String,String> {
        if(ids.isEmpty()) return emptyMap()
        val names=mutableMapOf<String,String>()
        ids.distinct().chunked(400).forEach { chunk ->
            val placeholders=chunk.joinToString(",") { "?" }
            connection.prepare("SELECT id,name FROM producers WHERE id IN ($placeholders)").use { s ->
                chunk.forEachIndexed { index,id -> s.bindText(index+1,id) }
                while(s.step()) names[s.getText(0)]=TextNorm.scrub(s.getText(1))
            }
        }
        return names
    }

    /** FROM clause with the selected scope filters plus its bind arguments, in order. */
    private fun scope(country:String?,source:String?):Pair<String,List<String>> {
        val from=if(trackSources) "producers p LEFT JOIN producer_sources s ON s.id=p.id" else "producers p"
        val clauses=mutableListOf<String>();val args=mutableListOf<String>()
        if(country!=null) { clauses+="p.country_group=?";args+=country }
        if(trackSources && source!=null) { clauses+="COALESCE(s.source,'lwin')=?";args+=source }
        return (from+if(clauses.isEmpty()) "" else clauses.joinToString(" AND "," WHERE ")) to args
    }

    private val sourceColumn:String get()=if(trackSources) ",COALESCE(s.source,'lwin')" else ""

    private fun contextOf(nation:String,sourceId:String?):String =
        if(!trackSources) nation else listOfNotNull(nation.ifBlank { null },sourceLabel(sourceId ?: "lwin")).joinToString(" · ")

    fun producerCount(country:String?=null,source:String?=null):Int {
        val (from,args)=scope(country,source)
        connection.prepare("SELECT COUNT(*) FROM $from").use { s ->
            args.forEachIndexed { index,arg -> s.bindText(index+1,arg) }
            check(s.step())
            return s.getLong(0).toInt()
        }
    }

    /** Page through every record in the selected scope or complete catalog, without fuzzy ranking. */
    fun browseProducers(country:String?=null,source:String?=null,offset:Int=0,limit:Int=50):List<DatabaseLookupMatch> {
        require(offset>=0 && limit in 1..200)
        val (from,args)=scope(country,source)
        val rows=mutableListOf<DatabaseLookupMatch>()
        connection.prepare("SELECT p.name,p.country_group$sourceColumn FROM $from ORDER BY p.name,p.id LIMIT ? OFFSET ?").use { s ->
            var parameter=1
            args.forEach { s.bindText(parameter++,it) }
            s.bindLong(parameter++,limit.toLong());s.bindLong(parameter,offset.toLong())
            while(s.step()) {
                val cleanName=TextNorm.scrub(s.getText(0))
                rows += DatabaseLookupMatch(
                    cleanName,contextOf(TextNorm.scrub(s.getText(1)),if(trackSources) s.getText(2) else null),cleanName,0,100
                )
            }
        }
        return rows
    }

    /**
     * Rank every producer in the selected scope, not only an FTS shortlist. Chunking bounds memory;
     * keeping each chunk's top N is sufficient to compute the global top N exactly.
     */
    fun searchProducers(query:String,country:String?=null,source:String?=null,limit:Int=12):List<DatabaseLookupMatch> {
        require(limit in 1..50)
        val normalized=producerKey(query)
        if(normalized.isBlank()) return emptyList()
        val (from,args)=scope(country,source)
        val ranked=mutableListOf<DatabaseLookupMatch>()
        var chunk=mutableListOf<DatabaseLookupCandidate>()
        fun rankChunk() {
            if(chunk.isNotEmpty()) {
                ranked += DatabaseNameLookup.rank(normalized,chunk,limit)
                chunk=mutableListOf()
            }
        }
        connection.prepare("SELECT p.id,p.name,p.key,p.country_group$sourceColumn FROM $from ORDER BY p.id").use { s ->
            args.forEachIndexed { index,arg -> s.bindText(index+1,arg) }
            while(s.step()) {
                val id=s.getText(0);val name=TextNorm.scrub(s.getText(1));val key=TextNorm.scrub(s.getText(2))
                val context=contextOf(TextNorm.scrub(s.getText(3)),if(trackSources) s.getText(4) else null)
                val identity=if(trackSources) "expanded-producer:$id" else "lwin-producer:$id"
                chunk += DatabaseLookupCandidate(identity=identity,name=name,context=context,searchTerms=listOf(name,key))
                if(chunk.size==512) rankChunk()
            }
        }
        rankChunk()
        return ranked.sortedWith(compareByDescending<DatabaseLookupMatch> { it.similarityPercent }
            .thenBy { it.editDistance }.thenBy { it.name.lowercase() }).take(limit)
    }

    override fun close()=connection.close()

    companion object {
        // Self-imposed sanity bound for copying a packaged catalog asset into the cache —
        // NOT an Android or SQLite limit. Raise it deliberately when a packaged catalog
        // legitimately grows past it; the practical constraints are APK size, ~2x on-device
        // storage (compressed asset + cache copy), and cold-install copy+hash time. The
        // benchmark budget loader in ProducerGazetteer carries the matching cap; keep them
        // in step. (Owner question, 2026-10-26.)
        private const val MAX_DATABASE_BYTES=64L*1024*1024

        /** Human-facing label for a producer_sources tag (also used by the lookup screen). */
        fun sourceLabel(source:String):String=when(source) {
            "lwin" -> "LWIN"
            "xwines" -> "X-Wines candidate"
            "ttb" -> "TTB permit candidate"
            "osm" -> "OpenStreetMap candidate"
            else -> source
        }

        fun open(context:Context,catalog:Catalog=Catalog.PRIMARY):ProducerCatalogDatabaseLookup {
            check(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper()) {
                "Open the producer catalog on Dispatchers.IO, never the UI thread"
            }
            val app=context.applicationContext
            val manifest=app.assets.open("${catalog.assetDir}/manifest.json").bufferedReader().use { JSONObject(it.readText()) }
            when(catalog) {
                Catalog.PRIMARY -> {
                    require(manifest.getInt("schema")==2 && manifest.getBoolean("primary_verified")) { "Verified producer catalog is required" }
                    require(manifest.getJSONObject("provenance").getString("sha256").matches(Regex("[0-9a-f]{64}")))
                }
                Catalog.EXPANDED -> require(
                    manifest.getInt("schema")==1 && manifest.getString("kind")=="lab-expanded-producer-index" && manifest.getBoolean("lab_only")
                ) { "Lab expanded producer catalog is required" }
            }
            val databaseHash=manifest.getString("database_sha256")
            require(databaseHash.matches(Regex("[0-9a-f]{64}")))
            val cache=File(app.cacheDir,catalog.assetDir).apply { mkdirs() }
            val target=File(cache,"$databaseHash.sqlite")
            if(!target.isFile || target.length() !in 1..MAX_DATABASE_BYTES || sha256(target)!=databaseHash) {
                val temporary=File.createTempFile("producer-catalog-",".tmp",cache)
                try {
                    app.assets.open("${catalog.assetDir}/${catalog.databaseName}").use { input ->
                        temporary.outputStream().use { output ->
                            val buffer=ByteArray(65536);var written=0L
                            while(true) {
                                val count=input.read(buffer);if(count<0) break
                                written+=count;require(written<=MAX_DATABASE_BYTES) { "Producer catalog exceeds size limit" }
                                output.write(buffer,0,count)
                            }
                        }
                    }
                    check(sha256(temporary)==databaseHash) { "Producer catalog checksum does not match its manifest" }
                    if(target.exists()) check(target.delete()) { "Could not replace corrupt producer catalog cache" }
                    check(temporary.renameTo(target)) { "Could not install the local producer catalog" }
                } finally { temporary.delete() }
            }
            val db=BundledSQLiteDriver().open(target.absolutePath)
            try {
                db.prepare("PRAGMA query_only=ON").use { it.step() }
                val count=db.prepare("SELECT COUNT(*) FROM producers").use { s -> check(s.step());s.getLong(0).toInt() }
                check(count==manifest.getInt("records")) { "Producer catalog count does not match its manifest" }
                if(catalog==Catalog.EXPANDED) {
                    val supplemental=db.prepare("SELECT COUNT(*) FROM producer_sources").use { s -> check(s.step());s.getLong(0).toInt() }
                    check(supplemental==manifest.getInt("supplemental_records")) { "Expanded producer source count does not match its manifest" }
                }
                db.prepare("SELECT id FROM names WHERE names MATCH '\"win\"' LIMIT 1").use { it.step() }
                return ProducerCatalogDatabaseLookup(db,count,catalog)
            } catch(failure:Throwable) {
                db.close();throw failure
            }
        }

        private fun producerKey(value:String):String=TextNorm.normalize(value)
            .replace(Regex("[^\\p{L}\\p{N}]+")," ").trim().replace(Regex("\\s+")," ")

        private fun sha256(file:File):String {
            val digest=MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer=ByteArray(65536)
                while(true) { val count=input.read(buffer);if(count<0) break;digest.update(buffer,0,count) }
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
    }
}
