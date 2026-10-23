package com.qingping.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** 本地 SQLite 统计，不联网。 */
class StatsStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "qingping.db", null, 1) {

    data class AppStat(val pkg: String, val skips: Int, val redirects: Int)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS events(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "pkg TEXT NOT NULL," +
                "kind TEXT NOT NULL," +
                "ts INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_events_pkg ON events(pkg)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 当前只有版本 1
    }

    /** kind: "skip" 或 "redirect_back" */
    fun record(pkg: String, kind: String) {
        val values = ContentValues().apply {
            put("pkg", pkg)
            put("kind", kind)
            put("ts", System.currentTimeMillis())
        }
        writableDatabase.insert("events", null, values)
    }

    fun summary(): List<AppStat> {
        val result = mutableListOf<AppStat>()
        readableDatabase.rawQuery(
            "SELECT pkg, " +
                "SUM(CASE WHEN kind='skip' THEN 1 ELSE 0 END), " +
                "SUM(CASE WHEN kind='redirect_back' THEN 1 ELSE 0 END) " +
                "FROM events GROUP BY pkg ORDER BY 2 DESC, 3 DESC",
            null
        ).use { c ->
            while (c.moveToNext()) {
                result += AppStat(c.getString(0), c.getInt(1), c.getInt(2))
            }
        }
        return result
    }

    fun clear() {
        writableDatabase.delete("events", null, null)
    }
}
