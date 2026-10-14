package com.yourapp.wine.kb

/** The same source SQL is exercised by the host SQLite checks; no binary KB is generated or shipped. */
internal object KnowledgeImportSql {
    val staging = listOf(
        "CREATE TABLE place(id INTEGER PRIMARY KEY,name TEXT NOT NULL,name_norm TEXT NOT NULL,type TEXT NOT NULL,parent_id INTEGER,country_code TEXT NOT NULL)",
        "CREATE TABLE producer(id INTEGER PRIMARY KEY,name TEXT NOT NULL,name_norm TEXT NOT NULL,country_code TEXT NOT NULL,region_id INTEGER)",
        "CREATE TABLE producer_alias(producer_id INTEGER NOT NULL,alias_norm TEXT NOT NULL,PRIMARY KEY(producer_id,alias_norm))",
        "CREATE TABLE cuvee(producer_id INTEGER NOT NULL,name TEXT NOT NULL,name_norm TEXT NOT NULL,PRIMARY KEY(producer_id,name_norm))",
    )
    const val MISSING_ALIAS_PRODUCER = "SELECT a.producer_id FROM producer_alias a LEFT JOIN producer p ON p.id=a.producer_id WHERE p.id IS NULL LIMIT 1"
    val transfer = listOf(
        "INSERT INTO place(id,name,name_norm,type,parent_id,country_code) SELECT id,name,name_norm,type,parent_id,country_code FROM incoming.place WHERE 1 ON CONFLICT(id) DO UPDATE SET name=excluded.name,name_norm=excluded.name_norm,type=excluded.type,parent_id=excluded.parent_id,country_code=excluded.country_code",
        "INSERT INTO producer(id,name,name_norm,country_code,region_id) SELECT id,name,name_norm,country_code,region_id FROM incoming.producer WHERE 1 ON CONFLICT(id) DO UPDATE SET name=excluded.name,name_norm=excluded.name_norm,country_code=excluded.country_code,region_id=excluded.region_id",
        "INSERT OR IGNORE INTO producer_alias(producer_id,alias_norm) SELECT producer_id,alias_norm FROM incoming.producer_alias",
        "INSERT INTO cuvee(producer_id,name,name_norm) SELECT producer_id,name,name_norm FROM incoming.cuvee WHERE 1 ON CONFLICT(producer_id,name_norm) DO UPDATE SET name=excluded.name",
    )
}
