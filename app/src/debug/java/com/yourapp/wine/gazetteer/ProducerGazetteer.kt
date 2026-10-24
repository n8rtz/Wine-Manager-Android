package com.yourapp.wine.gazetteer

import android.content.Context
import android.os.Looper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** No network and no mirror fallback. Missing/invalid primary asset is an explicit unavailable result. */
internal class ProducerGazetteer private constructor(
    private val db:SQLiteConnection,private val wines:SQLiteConnection,private val support:Map<String,SQLiteConnection>,override val version:String
):LinkedWineResolver.Index,AutoCloseable {
    override val primaryVerified=true
    override fun lookup(key:String,limit:Int,checkBudget:()->Unit):List<ProducerResolver.Entity> {
        require(limit in 1..ProducerResolver.MAX_FUZZY_CANDIDATES);checkBudget()
        fun read(sql:String,arg:String):List<ProducerResolver.Entity> {
            val rows=mutableListOf<ProducerResolver.Entity>()
            db.prepare(sql).use { s ->
                s.bindText(1,arg);s.bindLong(2,limit.toLong())
                while(s.step()) {
                    checkBudget();val id=s.getText(0);val name=s.getText(1);val normalized=s.getText(2)
                    val sources=support.mapNotNull { (source,connection) ->
                        checkBudget()
                        connection.prepare("SELECT id FROM support WHERE key=? LIMIT 1").use { q ->
                            q.bindText(1,normalized);if(q.step()) "$source:${q.getText(0)}" else null
                        }
                    }.toSet()
                    rows+=ProducerResolver.Entity(id,name,normalized,sources)
                }
            };return rows
        }
        val exact=read("SELECT id,name,key FROM producers WHERE key=? ORDER BY id LIMIT ?",key)
        if(exact.isNotEmpty()) return exact
        val query=key.windowed(3).filter { it.none(Char::isWhitespace) }.distinct().take(24).joinToString(" OR ") { "\"$it\"" }
        if(query.isBlank()) return emptyList()
        return read("SELECT p.id,p.name,p.key FROM names JOIN producers p ON names.id=p.id WHERE names MATCH ? ORDER BY bm25(names),p.id LIMIT ?",query)
    }
    override fun lookupWines(key:String,producerIds:Set<String>,limit:Int,checkBudget:()->Unit):List<LinkedWineResolver.Wine> {
        require(producerIds.size in 1..8 && limit in 1..ProducerResolver.MAX_FUZZY_CANDIDATES);checkBudget()
        val ids=producerIds.sorted();val slots=ids.joinToString(",") { "?" }
        fun query(fuzzy:Boolean,arg:String):List<LinkedWineResolver.Wine> {
            val from=if(fuzzy) "wine_fts JOIN wine_names n ON wine_fts.rowid=n.id" else "wine_names n"
            val predicate=if(fuzzy) "wine_fts MATCH ?" else "n.key=?"
            val order=if(fuzzy) "bm25(wine_fts)," else ""
            val sql="SELECT n.id,n.name,n.key,p.producer_id,l.lwin7 FROM $from JOIN wine_links l ON l.wine_id=n.id JOIN wine_producers p ON p.id=l.producer_id WHERE $predicate AND p.producer_id IN ($slots) ORDER BY ${order}n.id,p.producer_id LIMIT ?"
            val rows=mutableListOf<LinkedWineResolver.Wine>()
            wines.prepare(sql).use { q ->
                q.bindText(1,arg);ids.forEachIndexed { i,id -> q.bindText(i+2,id) };q.bindLong(ids.size+2,limit.toLong())
                while(q.step()) { checkBudget();rows+=LinkedWineResolver.Wine(q.getLong(0).toString(),q.getText(1),q.getText(2),q.getText(3),q.getText(4)) }
            };return rows
        }
        val exact=query(false,key)
        if(exact.isNotEmpty()) return exact
        val terms=key.windowed(3).filter { it.none(Char::isWhitespace) }.distinct().take(24).joinToString(" OR ") { "\"$it\"" }
        return if(terms.isBlank()) emptyList() else query(true,terms)
    }
    override fun close() { try { support.values.forEach { it.close() } } finally { try { wines.close() } finally { db.close() } } }
    companion object {
        const val ASSET="producer-gazetteer-v1"
        private fun sha(file:File,checkBudget:()->Unit):String {
            val md=MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val b=ByteArray(65536);while(true) { checkBudget();val n=input.read(b);if(n<0) break;md.update(b,0,n) } }
            return md.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        fun open(context:Context,checkBudget:()->Unit={}):ProducerGazetteer {
            check(Looper.myLooper()!=Looper.getMainLooper());checkBudget()
            val text=context.assets.open("$ASSET/manifest.json").bufferedReader().use { it.readText() }
            require(text.length<65536)
            val manifest=JSONObject(text)
            check(manifest.getInt("schema")==2 && manifest.getBoolean("primary_verified") &&
                manifest.getString("verification_kind")=="official-export-acquisition-attested") { "Primary LWIN export required" }
            check(manifest.getJSONObject("provenance").getString("acquisition")=="official_download_user_supplied")
            fun load(name:String,hash:String):SQLiteConnection {
                require(name.matches(Regex("[a-z-]+\\.sqlite")) && hash.matches(Regex("[0-9a-f]{64}")))
                checkBudget();val dir=File(context.cacheDir,ASSET).apply { mkdirs() };val target=File(dir,"$hash.sqlite")
                if(!target.exists() || sha(target,checkBudget)!=hash) {
                    val tmp=File.createTempFile("producer-",".tmp",dir)
                    try {
                        context.assets.open("$ASSET/$name").use { input -> tmp.outputStream().use { output ->
                            val b=ByteArray(65536);var total=0
                            while(true) { checkBudget();val n=input.read(b);if(n<0) break;total+=n;require(total<=32*1024*1024);output.write(b,0,n) }
                        } }
                        check(sha(tmp,checkBudget)==hash);checkBudget()
                        java.nio.file.Files.move(tmp.toPath(),target.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    } finally { tmp.delete() }
                }
                checkBudget();val db=BundledSQLiteDriver().open(target.absolutePath)
                try { db.prepare("PRAGMA query_only=ON").use { it.step() };return db }
                catch(e:Exception) { db.close();throw e }
            }
            val hash=manifest.getString("database_sha256")
            val db=load("producers.sqlite",hash);var wineDb:SQLiteConnection?=null;val support=mutableMapOf<String,SQLiteConnection>()
            try {
                db.prepare("SELECT COUNT(*) FROM producers").use { check(it.step() && it.getLong(0)==manifest.getLong("records")) }
                // Exercise the actual native FTS table, not merely a platform SQLite version string.
                db.prepare("SELECT id FROM names WHERE names MATCH '\"win\"' LIMIT 1").use { it.step() }
                val wineHash=manifest.getString("wine_database_sha256")
                val loadedWines=load("wines.sqlite",wineHash);wineDb=loadedWines
                loadedWines.prepare("SELECT COUNT(*) FROM wine_names").use { check(it.step() && it.getLong(0)==manifest.getLong("wine_names")) }
                loadedWines.prepare("SELECT COUNT(*) FROM wine_links").use { check(it.step() && it.getLong(0)==manifest.getLong("wine_links")) }
                loadedWines.prepare("SELECT rowid FROM wine_fts WHERE wine_fts MATCH '\"win\"' LIMIT 1").use { it.step() }
                val layers=manifest.optJSONObject("support")
                for(source in listOf("ttb","osm")) layers?.optJSONObject(source)?.let { item ->
                    support[source]=load(item.getString("file"),item.getString("sha256"))
                }
                checkBudget();return ProducerGazetteer(db,loadedWines,support,"${manifest.getString("version")}:producer=$hash;wine=$wineHash")
            } catch(e:Exception) { support.values.forEach { it.close() };wineDb?.close();db.close();throw e }
        }
    }
}
