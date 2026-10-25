package com.yourapp.wine.kb

import android.content.Context
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.yourapp.wine.merge.TextNorm
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Read-only lookup over the complete verified LWIN producer layer. Call only from a worker thread. */
class ProducerCatalogDatabaseLookup private constructor(
    private val connection: SQLiteConnection,
    val totalProducers: Int
) : AutoCloseable {
    data class Country(val name:String,val producerCount:Int)

    fun countries():List<Country> {
        val rows=mutableListOf<Country>()
        connection.prepare("SELECT country_group,COUNT(*) FROM producers GROUP BY country_group ORDER BY country_group").use { s ->
            while(s.step()) rows += Country(s.getText(0),s.getLong(1).toInt())
        }
        return rows
    }

    fun producerCount(country:String?=null):Int {
        val sql=if(country==null) "SELECT COUNT(*) FROM producers" else "SELECT COUNT(*) FROM producers WHERE country_group=?"
        connection.prepare(sql).use { s ->
            if(country!=null) s.bindText(1,country)
            check(s.step())
            return s.getLong(0).toInt()
        }
    }

    /** Page through every record in the selected country or complete catalog, without fuzzy ranking. */
    fun browseProducers(country:String?=null,offset:Int=0,limit:Int=50):List<DatabaseLookupMatch> {
        require(offset>=0 && limit in 1..200)
        val countryClause=if(country==null) "" else " WHERE country_group=?"
        val rows=mutableListOf<DatabaseLookupMatch>()
        connection.prepare("SELECT name,country_group FROM producers$countryClause ORDER BY name,id LIMIT ? OFFSET ?").use { s ->
            var parameter=1
            if(country!=null) s.bindText(parameter++,country)
            s.bindLong(parameter++,limit.toLong());s.bindLong(parameter,offset.toLong())
            while(s.step()) rows += DatabaseLookupMatch(s.getText(0),s.getText(1),s.getText(0),0,100)
        }
        return rows
    }

    /**
     * Rank every producer in the selected scope, not only an FTS shortlist. Chunking bounds memory;
     * keeping each chunk's top N is sufficient to compute the global top N exactly.
     */
    fun searchProducers(query:String,country:String?=null,limit:Int=12):List<DatabaseLookupMatch> {
        require(limit in 1..50)
        val normalized=producerKey(query)
        if(normalized.isBlank()) return emptyList()
        val countryClause=if(country==null) "" else " WHERE country_group=?"
        val ranked=mutableListOf<DatabaseLookupMatch>()
        var chunk=mutableListOf<DatabaseLookupCandidate>()
        fun rankChunk() {
            if(chunk.isNotEmpty()) {
                ranked += DatabaseNameLookup.rank(normalized,chunk,limit)
                chunk=mutableListOf()
            }
        }
        connection.prepare("SELECT id,name,key,country_group FROM producers$countryClause ORDER BY id").use { s ->
            if(country!=null) s.bindText(1,country)
            while(s.step()) {
                val id=s.getText(0);val name=s.getText(1);val key=s.getText(2);val nation=s.getText(3)
                chunk += DatabaseLookupCandidate(identity="lwin-producer:$id",name=name,context=nation,searchTerms=listOf(name,key))
                if(chunk.size==512) rankChunk()
            }
        }
        rankChunk()
        return ranked.sortedWith(compareByDescending<DatabaseLookupMatch> { it.similarityPercent }
            .thenBy { it.editDistance }.thenBy { it.name.lowercase() }).take(limit)
    }

    override fun close()=connection.close()

    companion object {
        private const val ASSET_DIR="producer-gazetteer-v1"
        private const val DATABASE_NAME="producers.sqlite"
        private const val MAX_DATABASE_BYTES=32L*1024*1024

        fun open(context:Context):ProducerCatalogDatabaseLookup {
            check(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper()) {
                "Open the producer catalog on Dispatchers.IO, never the UI thread"
            }
            val app=context.applicationContext
            val manifest=app.assets.open("$ASSET_DIR/manifest.json").bufferedReader().use { JSONObject(it.readText()) }
            require(manifest.getInt("schema")==2 && manifest.getBoolean("primary_verified")) { "Verified producer catalog is required" }
            val expectedHash=manifest.getJSONObject("provenance").getString("sha256")
            val databaseHash=manifest.getString("database_sha256")
            require(expectedHash.matches(Regex("[0-9a-f]{64}")) && databaseHash.matches(Regex("[0-9a-f]{64}")))
            val cache=File(app.cacheDir,ASSET_DIR).apply { mkdirs() }
            val target=File(cache,"$databaseHash.sqlite")
            if(!target.isFile || target.length() !in 1..MAX_DATABASE_BYTES || sha256(target)!=databaseHash) {
                val temporary=File.createTempFile("producer-catalog-",".tmp",cache)
                try {
                    app.assets.open("$ASSET_DIR/$DATABASE_NAME").use { input ->
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
                db.prepare("SELECT id FROM names WHERE names MATCH '\"win\"' LIMIT 1").use { it.step() }
                return ProducerCatalogDatabaseLookup(db,count)
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
