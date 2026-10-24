package com.yourapp.wine.gazetteer

import android.content.Context
import android.os.Looper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

/** Debug-only worker-confined resolver. BM25 is a shortlist rank, never a probability. */
internal class TrigramGazetteer private constructor(private val db:SQLiteConnection):AutoCloseable {
    data class Entity(val id:Long,val name:String,val country:String,val region:String,val subRegion:String,
        val level:String,val parentId:Long?,val officialCode:String,val source:String,val regionPath:String,val path:List<String>)
    data class Hit(val entity:Entity,val distance:Int,val similarity:Double,val matchedForm:String,val bm25:Double)
    data class Resolution(val raw:String,val query:String?,val accepted:Hit?,val alternatives:List<Hit>,val reason:String)
    companion object {
        const val VERSION="gazetteer-trigram-v1"
        private val noise=Regex("\\b(?:sulfites?|sulphites?|sulfit[ei]s?|allergen|pregnan\\w*|government|warning|importe[dr]|imported|bottled|bouteille|embouteille|distribu\\w*|contains|contient|www|https?|alc|vol|alcohol)\\b")
        private val estate=Regex("\\b(?:chateau|chatau|chateaux|domaine|bodega|winery|estate|maison)\\b")
        private val generic=setOf("merlot","chardonnay","cabernet sauvignon","pinot noir","red wine","white wine","grand cru","reserve","riserva","reserva","wine","vin")
        fun normalize(value:String):String {
            val n=Normalizer.normalize(value.lowercase(Locale.ROOT),Normalizer.Form.NFD).replace(Regex("\\p{M}+"),"")
                .map { if(it.isLetterOrDigit()) it else ' ' }.joinToString("").trim().split(Regex("\\s+")).filter(String::isNotBlank)
            return n.joinToString(" ") { when(it) { "st"->"saint";"ste"->"sainte";"mt"->"mount";else->it } }
        }
        fun distance(a:String,b:String):Int {
            var previous=IntArray(b.length+1) { it };var older:IntArray?=null
            for(i in a.indices) {
                val current=IntArray(b.length+1);current[0]=i+1
                for(j in b.indices) {
                    current[j+1]=minOf(current[j]+1,previous[j+1]+1,previous[j]+if(a[i]==b[j]) 0 else 1)
                    if(i>0 && j>0 && a[i]==b[j-1] && a[i-1]==b[j]) current[j+1]=minOf(current[j+1],older!![j-1]+1)
                }
                older=previous;previous=current
            }
            return previous[b.length]
        }
        fun clean(raw:String):String? {
            if(raw.length>160) return null
            val n=normalize(raw)
            if(n.length<3 || raw.any(Char::isDigit) || '%' in raw || noise.containsMatchIn(n) || estate.containsMatchIn(n) || n in generic) return null
            val words=n.split(' ').toMutableList()
            if(words.firstOrNull()=="appellation") words.removeAt(0)
            while(words.lastOrNull() in setOf("controlee","protegee","aoc","aop","doc","docg","dop","igp")) words.removeAt(words.lastIndex)
            if(words.lastOrNull()?.let { w -> w.length>=6 && listOf("riserva","reserva","reserve").any { distance(w,it)<=1 } }==true) words.removeAt(words.lastIndex)
            return words.joinToString(" ").takeIf { it.length>=3 && it !in generic }
        }
        fun query(q:String):String=q.windowed(3).filter { it.isNotBlank() }.distinct().sorted().take(96)
            .joinToString(" OR ","{canonical_name alternate_names} : (",")") { "\"${it.replace("\"","\"\"")}\"" }
        private fun sha(file:File):String {
            val digest=MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val buffer=ByteArray(65536);while(true) { val n=input.read(buffer);if(n<0) break;digest.update(buffer,0,n) } }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        @Synchronized fun open(context:Context):TrigramGazetteer {
            check(Looper.myLooper()!=Looper.getMainLooper()) { "Gazetteer I/O must run on a worker" }
            val manifest=JSONObject(context.assets.open("gazetteer-v1/manifest.json").bufferedReader().use { it.readText() })
            val expected=manifest.getString("database_sha256")
            require(expected.matches(Regex("[0-9a-f]{64}")))
            val dir=File(context.cacheDir,"gazetteer-v1").apply { mkdirs() };val file=File(dir,"$expected.sqlite")
            if(!file.exists() || sha(file)!=expected) {
                val temp=File.createTempFile("gazetteer-",".sqlite",dir)
                try {
                    context.assets.open("gazetteer-v1/wine_gazetteer.sqlite").use { input -> temp.outputStream().use { input.copyTo(it) } }
                    check(sha(temp)==expected) { "Gazetteer checksum mismatch" }
                    Files.move(temp.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE)
                } finally { temp.delete() }
            }
            val db=BundledSQLiteDriver().open(file.absolutePath)
            try {
                fun exec(sql:String) { db.prepare(sql).use { it.step() } }
                // Prove FTS5 + trigram on this actual bundled native runtime, not platform SQLite.
                exec("CREATE VIRTUAL TABLE temp.trigram_probe USING fts5(value,tokenize='trigram')")
                exec("INSERT INTO trigram_probe VALUES('pauillac')")
                db.prepare("SELECT count(*) FROM trigram_probe WHERE trigram_probe MATCH '\"pau\"'").use { check(it.step() && it.getLong(0)==1L) }
                exec("DROP TABLE trigram_probe");exec("PRAGMA query_only=ON")
                db.prepare("SELECT value FROM metadata WHERE key='schema_version'").use { check(it.step() && it.getText(0)=="1") }
                db.prepare("SELECT count(*) FROM entities").use { check(it.step() && it.getLong(0)==manifest.getLong("records")) }
                return TrigramGazetteer(db)
            } catch(error:Throwable) { db.close();throw error }
        }
    }
    fun resolve(raw:String,checkBudget:()->Unit={}):Resolution {
        check(Looper.myLooper()!=Looper.getMainLooper()) { "Gazetteer resolution must run on a worker" }
        checkBudget()
        val q=clean(raw) ?: return Resolution(raw,null,null,emptyList(),"filtered")
        val hits=mutableListOf<Hit>()
        db.prepare("SELECT e.*,bm25(appellations_fts) AS score FROM appellations_fts JOIN entities e ON e.id=appellations_fts.rowid WHERE appellations_fts MATCH ? ORDER BY score ASC,e.id ASC LIMIT 64").use { s ->
            s.bindText(1,query(q))
            while(s.step()) {
                checkBudget()
                fun text(i:Int)=if(s.isNull(i)) "" else s.getText(i)
                val aliases=JSONArray(text(10));val path=JSONArray(text(11))
                val forms=(0 until aliases.length()).map { normalize(aliases.getString(it)) }.distinct()
                val best=forms.map { distance(q,it) to it }.minWithOrNull(compareBy<Pair<Int,String>> { it.first }.thenBy { it.second }) ?: continue
                val entity=Entity(s.getLong(0),text(1),text(2),text(3),text(4),text(5),if(s.isNull(6)) null else s.getLong(6),text(7),text(8),text(9),(0 until path.length()).map(path::getString))
                hits+=Hit(entity,best.first,1.0-best.first.toDouble()/maxOf(q.length,best.second.length,1),best.second,s.getDouble(12))
            }
        }
        hits.sortWith(compareByDescending<Hit> { it.similarity }.thenBy { it.distance }.thenBy { it.bm25 }.thenBy { it.entity.id })
        val first=hits.firstOrNull() ?: return Resolution(raw,q,null,emptyList(),"no candidates")
        val length=maxOf(q.length,first.matchedForm.length);val maxEdits=if(length<5) 0 else if(length<9) 1 else 2
        val reason=when {
            first.distance>maxEdits || first.similarity<.82 -> "weak lexical evidence"
            hits.size>1 && first.similarity-hits[1].similarity<.06 -> "ambiguous entities"
            first.distance==0 -> "exact alias"
            else -> "bounded fuzzy suggestion"
        }
        return Resolution(raw,q,first.takeIf { reason=="exact alias" || reason=="bounded fuzzy suggestion" },hits.take(5),reason)
    }
    override fun close()=db.close()
}
