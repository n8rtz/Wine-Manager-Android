package com.yourapp.wine.kb

import android.content.Context
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.yourapp.wine.merge.TextNorm
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Read-only lookup over the packaged LWIN wine index (wines.sqlite) for the Database
 * Lookup page's Wine name search. Call only from a worker thread. The install/cache
 * layout matches ProducerGazetteer.load, so both share one on-device copy.
 * Owner rule (.agents/instructions.md #17): packaged databases must stay inspectable
 * on the Database Lookup page. */
class WineIndexDatabaseLookup private constructor(
    private val connection: SQLiteConnection,
    val totalWines: Int
) : AutoCloseable {

    /**
     * Rank every wine name in the index, not only an FTS shortlist (same exhaustive
     * diagnostic contract as the producer search). Results carry identity
     * "lwin-wine:<id>" and blank context; resolve producer context afterwards via
     * [producerIdsForWine] plus ProducerCatalogDatabaseLookup.producerNames.
     */
    fun searchWines(query:String,limit:Int=12):List<DatabaseLookupMatch> {
        require(limit in 1..50)
        val normalized=wineKey(query)
        if(normalized.isBlank()) return emptyList()
        val ranked=mutableListOf<DatabaseLookupMatch>()
        var chunk=mutableListOf<DatabaseLookupCandidate>()
        fun rankChunk() {
            if(chunk.isNotEmpty()) {
                ranked += DatabaseNameLookup.rank(normalized,chunk,limit)
                chunk=mutableListOf()
            }
        }
        connection.prepare("SELECT id,name,key FROM wine_names ORDER BY id").use { s ->
            while(s.step()) {
                val name=TextNorm.scrub(s.getText(1))
                val key=TextNorm.scrub(s.getText(2))
                chunk += DatabaseLookupCandidate(
                    identity="lwin-wine:${s.getLong(0)}",name=name,context="",
                    searchTerms=listOf(name,key)
                )
                if(chunk.size==512) rankChunk()
            }
        }
        rankChunk()
        return ranked.sortedWith(compareByDescending<DatabaseLookupMatch> { it.similarityPercent }
            .thenBy { it.editDistance }.thenBy { it.name.lowercase() }).take(limit)
    }

    /** LWIN producer ids (producers.sqlite keys) linked to a wine name. */
    fun producerIdsForWine(wineId:Long):List<String> {
        val ids=mutableListOf<String>()
        connection.prepare("SELECT p.producer_id FROM wine_links l JOIN wine_producers p ON p.id=l.producer_id WHERE l.wine_id=? ORDER BY p.producer_id").use { s ->
            s.bindLong(1,wineId)
            while(s.step()) ids += s.getText(0)
        }
        return ids.distinct()
    }

    override fun close()=connection.close()

    companion object {
        private const val ASSET_DIR="producer-gazetteer-v1"
        private const val DATABASE_NAME="wines.sqlite"
        // Self-imposed packaged-asset sanity bound, NOT a platform limit; keep in step
        // with ProducerCatalogDatabaseLookup.MAX_DATABASE_BYTES / ProducerGazetteer.load.
        private const val MAX_DATABASE_BYTES=64L*1024*1024

        fun open(context:Context):WineIndexDatabaseLookup {
            check(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper()) {
                "Open the wine index on Dispatchers.IO, never the UI thread"
            }
            val app=context.applicationContext
            val manifest=app.assets.open("$ASSET_DIR/manifest.json").bufferedReader().use { JSONObject(it.readText()) }
            require(manifest.getInt("schema")==2 && manifest.getBoolean("primary_verified")) { "Verified wine index is required" }
            val databaseHash=manifest.getString("wine_database_sha256")
            require(databaseHash.matches(Regex("[0-9a-f]{64}")))
            val cache=File(app.cacheDir,ASSET_DIR).apply { mkdirs() }
            val target=File(cache,"$databaseHash.sqlite")
            if(!target.isFile || target.length() !in 1..MAX_DATABASE_BYTES || sha256(target)!=databaseHash) {
                val temporary=File.createTempFile("wine-index-",".tmp",cache)
                try {
                    app.assets.open("$ASSET_DIR/$DATABASE_NAME").use { input ->
                        temporary.outputStream().use { output ->
                            val buffer=ByteArray(65536);var written=0L
                            while(true) {
                                val count=input.read(buffer);if(count<0) break
                                written+=count;require(written<=MAX_DATABASE_BYTES) { "Wine index exceeds size limit" }
                                output.write(buffer,0,count)
                            }
                        }
                    }
                    check(sha256(temporary)==databaseHash) { "Wine index checksum does not match its manifest" }
                    if(target.exists()) check(target.delete()) { "Could not replace corrupt wine index cache" }
                    check(temporary.renameTo(target)) { "Could not install the local wine index" }
                } finally { temporary.delete() }
            }
            val db=BundledSQLiteDriver().open(target.absolutePath)
            try {
                db.prepare("PRAGMA query_only=ON").use { it.step() }
                val wines=db.prepare("SELECT COUNT(*) FROM wine_names").use { s -> check(s.step());s.getLong(0) }
                check(wines==manifest.getLong("wine_names")) { "Wine index name count does not match its manifest" }
                val links=db.prepare("SELECT COUNT(*) FROM wine_links").use { s -> check(s.step());s.getLong(0) }
                check(links==manifest.getLong("wine_links")) { "Wine index link count does not match its manifest" }
                db.prepare("SELECT rowid FROM wine_fts WHERE wine_fts MATCH '\"win\"' LIMIT 1").use { it.step() }
                return WineIndexDatabaseLookup(db,wines.toInt())
            } catch(failure:Throwable) {
                db.close();throw failure
            }
        }

        private fun wineKey(value:String):String=TextNorm.normalize(value)
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
