#!/usr/bin/env python3
"""Exercise the production import/schema SQL with host SQLite FTS5, NOT the Android driver/JNI."""
import json
from pathlib import Path
import re
import sqlite3
import tempfile

root = Path(__file__).resolve().parents[1]
source = (root / 'app/src/main/java/com/yourapp/wine/kb/KnowledgeImportSql.kt').read_text()
main_source = (root / 'app/src/main/java/com/yourapp/wine/kb/SqliteWineKnowledgeBase.kt').read_text()

def literals(text):
    return [json.loads(value) for value in re.findall(r'"(?:\\.|[^"\\])*"', text)]

def sql_list(name):
    body = re.search(rf'val {name} = listOf\((.*?)\n    \)', source, re.S).group(1)
    return literals(body)

schema = [sql for sql in literals(main_source) if sql.startswith('CREATE ')]
staging = sql_list('staging')
transfer = sql_list('transfer')
missing_alias = re.search(r'const val MISSING_ALIAS_PRODUCER = ("[^\n]+")', source).group(1)
missing_alias = json.loads(missing_alias)
fts = next(sql for sql in literals(main_source) if sql.startswith('INSERT INTO producer_fts'))

scratch = root / '.ocr-harness/checks'
scratch.mkdir(parents=True, exist_ok=True)
with tempfile.TemporaryDirectory(prefix='sql-import-', dir=scratch) as directory:
    path = Path(directory)
    db = sqlite3.connect(path / 'main.sqlite', isolation_level=None)
    db.execute('PRAGMA foreign_keys=ON')
    for sql in schema:
        db.execute(sql)
    db.execute("INSERT INTO place VALUES(1,'France','france','country',NULL,'FR')")
    db.execute("INSERT INTO place VALUES(2,'Bordeaux','bordeaux','region',1,'FR')")
    db.execute("INSERT INTO producer VALUES(1,'Château Margaux','chateau margaux','FR',2)")
    db.execute(fts)

    stage = sqlite3.connect(path / 'incoming.sqlite', isolation_level=None)
    for sql in staging:
        stage.execute(sql)
    # Alias before producer and child before parent are valid in staging, regardless of JSON/CSV order.
    stage.execute("INSERT INTO producer_alias VALUES(2,'chateau latour')")
    assert stage.execute(missing_alias).fetchone() == (2,)
    stage.execute("INSERT INTO producer VALUES(2,'Château Latour','chateau latour','FR',2)")
    stage.execute("INSERT INTO place VALUES(99,'Oakville','oakville','appellation',100,'US')")
    stage.execute("INSERT INTO place VALUES(100,'Napa Valley','napa valley','subregion',101,'US')")
    stage.execute("INSERT INTO place VALUES(101,'California','california','region',102,'US')")
    stage.execute("INSERT INTO place VALUES(102,'United States','united states','country',NULL,'US')")
    stage.execute("INSERT INTO cuvee VALUES(1,'Pavillon Rouge','pavillon rouge')")
    assert stage.execute(missing_alias).fetchone() is None
    stage.close()

    db.execute('ATTACH DATABASE ? AS incoming', (str(path / 'incoming.sqlite'),))
    db.execute('BEGIN IMMEDIATE')
    for sql in transfer:
        db.execute(sql)
    db.execute('DELETE FROM producer_fts')
    db.execute(fts)
    db.execute('COMMIT')
    assert db.execute("SELECT rowid FROM producer_fts WHERE producer_fts MATCH 'latour'").fetchall() == [(2,)]
    assert db.execute('PRAGMA foreign_key_check').fetchall() == []
    db.execute('DETACH DATABASE incoming')
    print('PASS: production staging/transfer SQL, alias ordering, deferred parent IDs and real host FTS5')

    stage = sqlite3.connect(path / 'incoming.sqlite', isolation_level=None)
    stage.execute("UPDATE place SET parent_id=987654 WHERE id=99")
    stage.close()
    db.execute('ATTACH DATABASE ? AS incoming', (str(path / 'incoming.sqlite'),))
    before = db.execute('SELECT * FROM place ORDER BY id').fetchall()
    db.execute('BEGIN IMMEDIATE')
    try:
        for sql in transfer:
            db.execute(sql)
        db.execute('DELETE FROM producer_fts')
        db.execute(fts)
        db.execute('COMMIT')
        raise AssertionError('Expected deferred FK failure')
    except sqlite3.IntegrityError:
        db.execute('ROLLBACK')
    assert db.execute('SELECT * FROM place ORDER BY id').fetchall() == before
    assert db.execute("SELECT rowid FROM producer_fts WHERE producer_fts MATCH 'latour'").fetchall() == [(2,)]
    db.execute('DETACH DATABASE incoming')
    db.close()
    print('PASS: production transfer + FTS rebuild roll back together on invalid foreign keys')
