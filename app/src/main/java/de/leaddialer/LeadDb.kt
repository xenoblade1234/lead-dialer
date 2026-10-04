package de.leaddialer

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class LeadDb(context: Context) : SQLiteOpenHelper(context, "leads.db", null, 3) {

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
                last_call INTEGER NOT NULL DEFAULT 0,
                list TEXT NOT NULL DEFAULT '',
                position INTEGER NOT NULL DEFAULT 0
            )"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v2 adds import lists; existing leads land in the unnamed list "".
        if (oldVersion < 2) db.execSQL("ALTER TABLE leads ADD COLUMN list TEXT NOT NULL DEFAULT ''")
        // v3 adds a manual order; existing leads keep their import order.
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE leads ADD COLUMN position INTEGER NOT NULL DEFAULT 0")
            db.execSQL("UPDATE leads SET position = id")
        }
    }

    /** All leads, or only those of one list when [list] is not null. */
    fun all(list: String? = null): List<Lead> =
        if (list == null) query("SELECT $COLS FROM leads ORDER BY position, id", emptyArray())
        else query("SELECT $COLS FROM leads WHERE list = ? ORDER BY position, id", arrayOf(list))

    fun get(id: Long): Lead? = query("SELECT $COLS FROM leads WHERE id = ?", arrayOf(id.toString())).firstOrNull()

    /** Leads still worth calling, in the manual (drag and drop) order. */
    fun queue(maxAttempts: Int, list: String? = null): List<Long> {
        val args = arrayListOf(maxAttempts.toString())
        val listFilter = if (list != null) { args.add(list); " AND list = ?" } else ""
        return query(
            """SELECT $COLS FROM leads
               WHERE (status IN ('NEU', 'RUECKRUF')
                  OR (status IN ('NICHT_ERREICHT', 'MAILBOX') AND attempts < ?))$listFilter
               ORDER BY position, id""",
            args.toTypedArray()
        ).map { it.id }
    }

    /** Every list with its lead count, newest import first. */
    fun lists(): List<Pair<String, Int>> =
        readableDatabase.rawQuery("SELECT list, COUNT(*) FROM leads GROUP BY list ORDER BY MAX(id) DESC", null).use { c ->
            val out = ArrayList<Pair<String, Int>>()
            while (c.moveToNext()) out.add((c.getString(0) ?: "") to c.getInt(1))
            out
        }

    /** Inserts leads, skipping numbers already in the same list. Returns how many were added. */
    fun insertAll(leads: List<Lead>): Int {
        val db = writableDatabase
        val known = HashSet<String>()
        db.rawQuery("SELECT list, phone FROM leads", null).use { c ->
            while (c.moveToNext()) known.add(c.getString(0) + "\u0000" + c.getString(1))
        }
        // New leads go to the end of the order, in file order.
        var next = db.rawQuery("SELECT COALESCE(MAX(position), 0) FROM leads", null).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L } + 1
        var added = 0
        db.beginTransaction()
        try {
            for (lead in leads) {
                if (known.add(lead.list + "\u0000" + lead.phone)) {
                    lead.position = next++
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

    /** Puts a deleted lead back exactly as it was (undo). */
    fun restore(lead: Lead) {
        val v = values(lead).apply { put("id", lead.id) }
        writableDatabase.insertWithOnConflict("leads", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /**
     * Saves a new order for the given leads. Reuses their current position values, so leads of
     * other lists that sit between them (in the "all lists" order) stay where they are.
     */
    fun reorder(ids: List<Long>) {
        if (ids.isEmpty()) return
        val db = writableDatabase
        val positions = ArrayList<Long>()
        db.rawQuery("SELECT position FROM leads WHERE id IN (${ids.joinToString(",")}) ORDER BY position, id", null).use { c ->
            while (c.moveToNext()) positions.add(c.getLong(0))
        }
        if (positions.size != ids.size) return
        // Equal positions (e.g. after a migration edge case) would make the order ambiguous.
        for (i in 1 until positions.size) if (positions[i] <= positions[i - 1]) positions[i] = positions[i - 1] + 1
        db.beginTransaction()
        try {
            ids.forEachIndexed { i, id ->
                db.execSQL("UPDATE leads SET position = ? WHERE id = ?", arrayOf<Any>(positions[i], id))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun update(lead: Lead) {
        writableDatabase.update("leads", values(lead), "id = ?", arrayOf(lead.id.toString()))
    }

    fun delete(id: Long) {
        writableDatabase.delete("leads", "id = ?", arrayOf(id.toString()))
    }

    fun deleteList(list: String) {
        writableDatabase.delete("leads", "list = ?", arrayOf(list))
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
        list = getString(8) ?: "",
        position = getLong(9),
    )

    private fun values(l: Lead) = ContentValues().apply {
        put("name", l.name)
        put("phone", l.phone)
        put("company", l.company)
        put("note", l.note)
        put("status", l.status.name)
        put("attempts", l.attempts)
        put("last_call", l.lastCall)
        put("list", l.list)
        put("position", l.position)
    }

    private companion object {
        const val COLS = "id, name, phone, company, note, status, attempts, last_call, list, position"
    }
}
