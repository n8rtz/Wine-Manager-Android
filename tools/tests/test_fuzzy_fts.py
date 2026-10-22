"""Execute the bounded producer-prefix SQL shape on real host SQLite FTS5, not Android inference."""
import sqlite3
import unittest

class FuzzyFtsTests(unittest.TestCase):
    def test_missing_first_letter_and_bound_parameters(self):
        db=sqlite3.connect(':memory:')
        db.executescript('CREATE TABLE producer(id INTEGER PRIMARY KEY,name TEXT,country_code TEXT,region_id INTEGER);'
                         'CREATE VIRTUAL TABLE producer_fts USING fts5(name);'
                         "INSERT INTO producer VALUES(1,'Blackstone Winery','US',19);"
                         "INSERT INTO producer_fts(rowid,name) VALUES(1,'Blackstone Winery Blackstone');")
        query=' OR '.join('"'+letter+'lac"*' for letter in 'abcdefghijklmnopqrstuvwxyz')
        rows=db.execute('SELECT p.name FROM producer_fts JOIN producer p ON p.id=producer_fts.rowid '
                        'WHERE producer_fts MATCH ? ORDER BY bm25(producer_fts) LIMIT 25',(query,)).fetchall()
        self.assertEqual(rows,[('Blackstone Winery',)])
        db.close()
