package de.leaddialer

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class LeadDb(context: Context) : SQLiteOpenHelper(context, "leads.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE leads (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL DEFAULT '',
                phone TEXT NOT NULL,
                company TEXT NOT NULL DEFAULT '',
                note TEXT NOT NULL DEFAULT '',
                status TEXT NOT NULL DEFAULT 'NEU',
                attempts INTEGER NOT NULL DEFAULT 0,
                last_call INTEGER NOT NULL DEFAULT 0
            )"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun all(): List<Lead> = query("SELECT $COLS FROM leads ORDER BY id", emptyArray())

    fun get(id: Long): Lead? = query("SELECT $COLS FROM leads WHERE id = ?", arrayOf(id.toString())).firstOrNull()

    /** Leads still worth calling: new ones first, then retries, oldest attempt first. */
    fun queue(maxAttempts: Int): List<Long> = query(
        """SELECT $COLS FROM leads
           WHERE status IN ('NEU', 'RUECKRUF')
              OR (status IN ('NICHT_ERREICHT', 'MAILBOX') AND attempts < ?)
           ORDER BY CASE status WHEN 'NEU' THEN 0 ELSE 1 END, last_call, id""",
        arrayOf(maxAttempts.toString())
    ).map { it.id }

    /** Inserts leads, skipping phone numbers that already exist. Returns how many were added. */
    fun insertAll(leads: List<Lead>): Int {
        val db = writableDatabase
        val known = HashSet<String>()
        db.rawQuery("SELECT phone FROM leads", null).use { c -> while (c.moveToNext()) known.add(c.getString(0)) }
        var added = 0
        db.beginTransaction()
        try {
            for (lead in leads) {
                if (known.add(lead.phone)) {
                    db.insert("leads", null, values(lead))
                    added++
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return added
    }

    fun update(lead: Lead) {
        writableDatabase.update("leads", values(lead), "id = ?", arrayOf(lead.id.toString()))
    }

    fun delete(id: Long) {
        writableDatabase.delete("leads", "id = ?", arrayOf(id.toString()))
    }

    fun deleteAll() {
        writableDatabase.delete("leads", null, null)
    }

    private fun query(sql: String, args: Array<String>): List<Lead> =
        readableDatabase.rawQuery(sql, args).use { c ->
            val out = ArrayList<Lead>()
            while (c.moveToNext()) out.add(c.toLead())
            out
        }

    private fun Cursor.toLead() = Lead(
        id = getLong(0),
        name = getString(1) ?: "",
        phone = getString(2) ?: "",
        company = getString(3) ?: "",
        note = getString(4) ?: "",
        status = Status.parse(getString(5)),
        attempts = getInt(6),
        lastCall = getLong(7),
    )

    private fun values(l: Lead) = ContentValues().apply {
        put("name", l.name)
        put("phone", l.phone)
        put("company", l.company)
        put("note", l.note)
        put("status", l.status.name)
        put("attempts", l.attempts)
        put("last_call", l.lastCall)
    }

    private companion object {
        const val COLS = "id, name, phone, company, note, status, attempts, last_call"
    }
}
