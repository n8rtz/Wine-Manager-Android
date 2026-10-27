package com.yourapp.wine.kb

import android.content.Context
import android.os.Looper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.yourapp.wine.merge.TextNorm
import java.io.InputStream
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Separate scanner DB: real bundled SQLite supplies FTS5 even on Android 26 (platform FTS5 is not assumed). */
class SqliteWineKnowledgeBase private constructor(private val connection: SQLiteConnection, private val otherAssetBytes: Long, private val importDirectory: File) : WineKnowledgeBase, OpenWinesImporter, AutoCloseable {
    private val lock = ReentrantLock()
    private var closed = false
    @Volatile private var gazetteer: List<Place> = emptyList()

    /** No coordinates: returns the cached local hierarchy; never does network I/O. */
    override fun places(): List<Place> = gazetteer

    /** No coordinates: real FTS5 top-25 shortlist followed by bounded Levenshtein reranking. */
    override fun findProducer(text: String): List<ProducerHit> = access {
        val words = Regex("[\\p{L}\\p{N}]{3,}").findAll(TextNorm.normalize(text))
            .map { it.value.take(4) }.distinct().take(8).toList()
        if (words.isEmpty()) return@access emptyList()
        val ftsQuery = words.joinToString(" OR ") { "\"$it\"*" }
        val producers = mutableListOf<Producer>()
        connection.prepare("SELECT p.id,p.name,p.country_code,p.region_id FROM producer_fts JOIN producer p ON p.id=producer_fts.rowid WHERE producer_fts MATCH ? ORDER BY bm25(producer_fts) LIMIT 25").use { statement ->
            statement.bindText(1, ftsQuery)
            while (statement.step()) producers += Producer(statement.getLong(0), statement.getText(1),
                statement.getText(2), if (statement.isNull(3)) null else statement.getLong(3))
        }
        KbMatching.rank(text, producers.map { it.copy(aliases = aliases(it.id)) })
    }

    override fun findProducerFuzzy(text:String):List<ProducerHit> = access {
        val words=KbMatching.fuzzyPrefixes(text)
        if(words.isEmpty()) return@access emptyList()
        val query=words.joinToString(" OR ") { "\"$it\"*" }
        val producers=mutableListOf<Producer>()
        connection.prepare("SELECT p.id,p.name,p.country_code,p.region_id FROM producer_fts JOIN producer p ON p.id=producer_fts.rowid WHERE producer_fts MATCH ? ORDER BY bm25(producer_fts) LIMIT 25").use { statement ->
            statement.bindText(1,query)
            while(statement.step()) producers+=Producer(statement.getLong(0),statement.getText(1),statement.getText(2),
                if(statement.isNull(3)) null else statement.getLong(3))
        }
        KbMatching.rank(text,producers.map { it.copy(aliases=aliases(it.id)) })
    }

    /** Exhaustive local-catalog lookup for the Settings diagnostic; always returns the closest entries, not corrections. */
    fun searchDiagnostic(category: DatabaseLookupCategory, query: String, limit: Int = 12): List<DatabaseLookupMatch> = access {
        require(limit in 1..50)
        val candidates = when (category) {
            DatabaseLookupCategory.PRODUCER -> {
                val grouped = linkedMapOf<Long, DatabaseLookupCandidate>()
                connection.prepare("SELECT p.id,p.name,p.country_code,COALESCE(r.name,''),a.alias_norm FROM producer p LEFT JOIN place r ON r.id=p.region_id LEFT JOIN producer_alias a ON a.producer_id=p.id ORDER BY p.id").use { s ->
                    while (s.step()) {
                        val id = s.getLong(0)
                        val region = s.getText(3)
                        val context = listOf(region, s.getText(2)).filter(String::isNotBlank).joinToString(" · ")
                        val alias = if (s.isNull(4)) null else s.getText(4)
                        val existing = grouped[id]
                        grouped[id] = if (existing == null) DatabaseLookupCandidate(
                            identity = "producer:$id", name = s.getText(1), context = context,
                            searchTerms = listOfNotNull(s.getText(1), alias)
                        ) else existing.copy(searchTerms = existing.searchTerms + listOfNotNull(alias))
                    }
                }
                grouped.values.toList()
            }
            DatabaseLookupCategory.WINE_NAME -> {
                val candidates = mutableListOf<DatabaseLookupCandidate>()
                connection.prepare("SELECT c.producer_id,c.name,p.name FROM cuvee c JOIN producer p ON p.id=c.producer_id ORDER BY c.name").use { s ->
                    while (s.step()) candidates += DatabaseLookupCandidate(
                        identity = "cuvee:${s.getLong(0)}:${TextNorm.normalize(s.getText(1))}",
                        name = s.getText(1), context = "Producer: ${s.getText(2)}"
                    )
                }
                candidates
            }
            // Raw place table only. The Database Lookup page instead searches the
            // supplemented origins view (SupplementedWineKnowledgeBase.places()), which
            // is where the bundled appellation database becomes visible at runtime.
            DatabaseLookupCategory.APPELLATION -> DatabaseNameLookup.appellationCandidates(gazetteer)
        }
        DatabaseNameLookup.rank(query, candidates, limit)
    }

    /** Wine-name records searchable by [searchDiagnostic] (Database Lookup database listing). */
    fun wineNameCount(): Int = access {
        connection.prepare("SELECT COUNT(*) FROM cuvee c JOIN producer p ON p.id=c.producer_id").use { s ->
            check(s.step()); s.getLong(0).toInt()
        }
    }

    override fun findCuvee(producerId:Long,text:String):CuveeHit? = findCuvees(producerId,text).firstOrNull()
    override fun findCuvees(producerId:Long,text:String):List<CuveeHit> = access {
        val matches=mutableListOf<CuveeHit>()
        connection.prepare("SELECT name,name_norm FROM cuvee WHERE producer_id=?").use { statement ->
            statement.bindLong(1,producerId)
            while(statement.step()) TextNorm.editDistance(TextNorm.normalize(text),statement.getText(1))?.let {
                matches+=CuveeHit(Cuvee(producerId,statement.getText(0)),it)
                if(matches.size>8) { matches.sortBy { hit -> hit.editDistance };matches.removeAt(matches.lastIndex) }
            }
        }
        matches.sortedBy { it.editDistance }.take(8)
    }

    /** No coordinates: imports only the stated adapter schema, on a worker thread, with atomic FK/hierarchy checks. */
    override fun importOpenWines(csvOrJson: InputStream) {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Import the wine KB on Dispatchers.Default, never the UI thread" }
        SqliteImportStaging.read(importDirectory, csvOrJson).use { staged ->
            lock.withLock {
                check(!closed) { "Wine KB is closed" }
                connection.prepare("ATTACH DATABASE ? AS incoming").use { statement ->
                    statement.bindText(1, staged.file.absolutePath); statement.step()
                }
                try {
                    val updated = transaction {
                        KnowledgeImportSql.transfer.forEach(::exec)
                        val places = readPlaces()
                        validateHierarchy(places)
                        rebuildFts()
                        checkDatabaseBudget()
                        places
                    }
                    gazetteer = updated // Publish only a committed, complete immutable snapshot.
                } finally { exec("DETACH DATABASE incoming") }
            }
        }
    }

    /** No coordinates: idempotently closes an import/test handle; app-scoped resources live for the process. */
    override fun close() = lock.withLock {
        if (!closed) { closed = true; connection.close() }
    }

    private inline fun <T> access(block: () -> T): T {
        // An importer must not hold recognition on a Java monitor for seconds past the shutter budget.
        check(lock.tryLock(20, TimeUnit.MILLISECONDS)) { "Local wine index busy; review partial OCR" }
        try { check(!closed) { "Wine KB is closed" }; return block() }
        finally { lock.unlock() }
    }

    private fun checkDatabaseBudget() {
        val pages = connection.prepare("PRAGMA page_count").use { it.step(); it.getLong(0) }
        val pageSize = connection.prepare("PRAGMA page_size").use { it.step(); it.getLong(0) }
        require(pages * pageSize + otherAssetBytes < 80L * 1024 * 1024) { "KB + supplied OCR assets exceed the 80 MiB optional-asset budget" }
    }

    private fun aliases(id: Long): List<String> {
        val result = mutableListOf<String>()
        connection.prepare("SELECT alias_norm FROM producer_alias WHERE producer_id=?").use { s ->
            s.bindLong(1, id)
            while (s.step()) result += s.getText(0)
        }
        return result
    }

    private fun exec(sql: String) { connection.prepare(sql).use { it.step() } }
    private fun <T> transaction(block: () -> T): T {
        exec("BEGIN IMMEDIATE")
        try { val value = block(); exec("COMMIT"); return value }
        catch (error: Throwable) {
            try { exec("ROLLBACK") } catch (rollback: Throwable) { error.addSuppressed(rollback) }
            throw error
        }
    }

    private fun insert(data: KnowledgeSnapshot) {
        for (p in data.places) connection.prepare("INSERT INTO place(id,name,name_norm,type,parent_id,country_code) VALUES(?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET name=excluded.name,name_norm=excluded.name_norm,type=excluded.type,parent_id=excluded.parent_id,country_code=excluded.country_code").use { s ->
            s.bindLong(1, p.id); s.bindText(2, p.name); s.bindText(3, p.nameNorm); s.bindText(4, p.type)
            p.parentId?.let { s.bindLong(5, it) } ?: s.bindNull(5)
            s.bindText(6, p.countryCode); s.step()
        }
        for (p in data.producers) {
            connection.prepare("INSERT INTO producer(id,name,name_norm,country_code,region_id) VALUES(?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET name=excluded.name,name_norm=excluded.name_norm,country_code=excluded.country_code,region_id=excluded.region_id").use { s ->
                s.bindLong(1, p.id); s.bindText(2, p.name); s.bindText(3, p.nameNorm); s.bindText(4, p.countryCode)
                p.regionId?.let { s.bindLong(5, it) } ?: s.bindNull(5); s.step()
            }
            for (alias in p.aliases) connection.prepare("INSERT OR IGNORE INTO producer_alias(producer_id,alias_norm) VALUES(?,?)").use { s ->
                s.bindLong(1, p.id); s.bindText(2, TextNorm.normalize(alias)); s.step()
            }
        }
        for (c in data.cuvees) connection.prepare("INSERT INTO cuvee(producer_id,name,name_norm) VALUES(?,?,?) ON CONFLICT(producer_id,name_norm) DO UPDATE SET name=excluded.name").use { s ->
            s.bindLong(1, c.producerId); s.bindText(2, c.name); s.bindText(3, TextNorm.normalize(c.name)); s.step()
        }
    }

    private fun rebuildFts() {
        exec("DELETE FROM producer_fts")
        exec("INSERT INTO producer_fts(rowid,name_norm,aliases) SELECT p.id,p.name_norm,COALESCE((SELECT group_concat(a.alias_norm,' ') FROM producer_alias a WHERE a.producer_id=p.id),'') FROM producer p")
    }

    private fun readPlaces(): List<Place> {
        val result = mutableListOf<Place>()
        connection.prepare("SELECT id,name,type,parent_id,country_code FROM place").use { s ->
            while (s.step()) result += Place(s.getLong(0), s.getText(1), s.getText(2),
                if (s.isNull(3)) null else s.getLong(3), s.getText(4))
        }
        return result
    }

    private fun validateHierarchy(places: List<Place>) {
        val byId = places.associateBy { it.id }
        for (p in places) {
            require(p.id > 0 && p.name.isNotBlank() && p.countryCode.matches(Regex("[A-Z]{2}"))) { "Invalid place ${p.id}" }
            if (p.type == "country") require(p.parentId == null) { "Country cannot have a parent" }
            else {
                val parent = requireNotNull(byId[p.parentId]) { "Missing parent for ${p.name}" }
                require(parent.countryCode == p.countryCode) { "Country conflict for ${p.name}" }
                require(when (p.type) {
                    "region" -> parent.type == "country"
                    "subregion", "appellation" -> parent.type in setOf("region", "subregion", "appellation")
                    else -> false
                }) { "Invalid hierarchy type for ${p.name}" }
                val seen = mutableSetOf<Long>()
                var node: Place? = p
                while (node != null) {
                    require(seen.add(node.id)) { "Hierarchy cycle at ${p.name}" }
                    node = node.parentId?.let(byId::get)
                }
            }
        }
        connection.prepare("SELECT p.name FROM producer p LEFT JOIN place r ON r.id=p.region_id WHERE p.region_id IS NOT NULL AND (r.type!='region' OR r.country_code!=p.country_code)").use { s ->
            require(!s.step()) { "Producer region/country conflict" }
        }
    }

    companion object {
        /** No coordinates: opens/seeds a genuine source-authored fixture DB; call from Dispatchers.Default. */
        fun open(context: Context, otherAssetBytes: Long = 0): SqliteWineKnowledgeBase {
            check(Looper.myLooper() != Looper.getMainLooper()) { "Open the wine KB on Dispatchers.Default, never the UI thread" }
            require(otherAssetBytes >= 0)
            val db = SqliteWineKnowledgeBase(BundledSQLiteDriver().open(context.getDatabasePath("wine-label-kb.sqlite").let {
                it.parentFile?.mkdirs(); it.absolutePath
            }), otherAssetBytes, context.cacheDir)
            try {
                db.exec("PRAGMA foreign_keys=ON")
                db.exec("PRAGMA journal_mode=DELETE")
                db.exec("PRAGMA busy_timeout=30")
                db.exec("PRAGMA cache_size=-2048")
                val version = db.connection.prepare("PRAGMA user_version").use { it.step(); it.getLong(0) }
                if (version == 0L) db.transaction {
                    db.exec("CREATE TABLE place(id INTEGER PRIMARY KEY,name TEXT NOT NULL,name_norm TEXT NOT NULL,type TEXT NOT NULL CHECK(type IN ('country','region','subregion','appellation')),parent_id INTEGER REFERENCES place(id) DEFERRABLE INITIALLY DEFERRED,country_code TEXT NOT NULL)")
                    db.exec("CREATE INDEX place_norm_idx ON place(name_norm)")
                    db.exec("CREATE TABLE producer(id INTEGER PRIMARY KEY,name TEXT NOT NULL,name_norm TEXT NOT NULL,country_code TEXT NOT NULL,region_id INTEGER REFERENCES place(id) DEFERRABLE INITIALLY DEFERRED)")
                    db.exec("CREATE INDEX producer_norm_idx ON producer(name_norm)")
                    db.exec("CREATE TABLE producer_alias(producer_id INTEGER NOT NULL REFERENCES producer(id),alias_norm TEXT NOT NULL,PRIMARY KEY(producer_id,alias_norm))")
                    db.exec("CREATE TABLE cuvee(producer_id INTEGER NOT NULL REFERENCES producer(id),name TEXT NOT NULL,name_norm TEXT NOT NULL,PRIMARY KEY(producer_id,name_norm))")
                    db.exec("CREATE VIRTUAL TABLE producer_fts USING fts5(name_norm,aliases,tokenize='unicode61 remove_diacritics 2')")
                    db.insert(FixtureKnowledgeBase.snapshot)
                    db.validateHierarchy(db.readPlaces())
                    db.rebuildFts()
                    db.exec("PRAGMA user_version=1")
                }
                require(version in 0L..1L) { "Unsupported wine KB schema: $version" }
                db.checkDatabaseBudget()
                db.gazetteer = db.readPlaces()
                return db
            } catch (error: Throwable) { db.close(); throw error }
        }
    }
}
