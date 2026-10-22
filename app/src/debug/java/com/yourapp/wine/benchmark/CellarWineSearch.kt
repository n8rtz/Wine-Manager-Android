package com.yourapp.wine.benchmark

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Debug-only read access. Never creates/migrates the cellar DB, fetches images, or modifies inventory. */
internal object CellarWineSearch {
    const val LIMIT=60
    suspend fun find(context:Context,query:String):List<CellarWineChoice> = withContext(Dispatchers.IO) {
        val file=context.getDatabasePath("wine-database")
        if(!file.isFile) return@withContext emptyList()
        SQLiteDatabase.openDatabase(file.path,null,SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("""SELECT b.bottleId,d.maker,d.name,d.country,d.region,d.subregion,b.vintage
                FROM wine_bottles b JOIN wine_definitions d ON d.definitionId=b.definitionId
                WHERE b.status='IN_CELLAR' AND b.quantity>0
                ORDER BY d.maker COLLATE NOCASE,d.name COLLATE NOCASE,b.vintage DESC,b.bottleId""",null).use { cursor ->
                val found=mutableListOf<CellarWineChoice>()
                while(cursor.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    fun text(column:Int)=if(cursor.isNull(column)) "" else cursor.getString(column)
                    val wine=CellarWineChoice(cursor.getLong(0),text(1),text(2),text(3),text(4),text(5),text(6))
                    if(wine.matches(query)) found+=wine
                    if(found.size==LIMIT) break
                }
                found
            }
        }
    }
}
