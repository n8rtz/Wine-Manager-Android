"""Exercise the debug picker's actual SELECT against host SQLite, not an Android UI test."""
import pathlib,re,sqlite3,unittest
ROOT=pathlib.Path(__file__).resolve().parents[2]
class OcrLabCellarTests(unittest.TestCase):
    def test_picker_reads_only_active_positive_inventory_and_keeps_vintages(self):
        source=(ROOT/'app/src/debug/java/com/yourapp/wine/benchmark/CellarWineSearch.kt').read_text()
        query=re.search(r'rawQuery\("""(.*?)""",null\)',source,re.S).group(1)
        self.assertIn('SQLiteDatabase.OPEN_READONLY',source)
        with sqlite3.connect(':memory:') as db:
            db.executescript('''
                CREATE TABLE wine_definitions(definitionId INTEGER,maker TEXT,name TEXT,country TEXT,region TEXT,subregion TEXT);
                CREATE TABLE wine_bottles(bottleId INTEGER,definitionId INTEGER,vintage TEXT,status TEXT,quantity INTEGER);
                INSERT INTO wine_definitions VALUES (1,'Maker','Wine','California',NULL,NULL);
                INSERT INTO wine_bottles VALUES (11,1,'2018','IN_CELLAR',1),(12,1,'2022','IN_CELLAR',2),
                    (13,1,'2020','ARCHIVED',1),(14,1,'2019','IN_CELLAR',0);
            ''')
            rows=db.execute(query).fetchall()
            self.assertEqual([r[0] for r in rows],[12,11])
            self.assertEqual([r[6] for r in rows],['2022','2018'])
            self.assertEqual(rows[0][1:6],('Maker','Wine','California',None,None))
            self.assertEqual(db.execute('SELECT COUNT(*) FROM wine_bottles').fetchone()[0],4)
