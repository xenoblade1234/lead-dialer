package de.leaddialer

import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CsvIO {

    /** 27.9.2026 or 9/27/2026: digits only, so it must not pass for a phone number. */
    private val DATE = Regex("""\d{1,4}[./-]\d{1,2}[./-]\d{1,4}""")

    /** German Excel often saves as Windows-1252 instead of UTF-8, so fall back when UTF-8 fails. */
    fun decode(bytes: ByteArray): String {
        var text = String(bytes, Charsets.UTF_8)
        if (text.contains('�')) text = String(bytes, Charset.forName("windows-1252"))
        return text.removePrefix("﻿")
    }

    /**
     * Normalizes a number for dialing. Excel drops the leading "+" when it stores a number as a
     * value, so a long number with neither "+" nor a leading 0 (e.g. 436705560222) gets the "+"
     * back; national numbers start with 0 and stay as they are.
     */
    fun cleanPhone(raw: String): String {
        val s = raw.replace("(0)", "").trim()
        val digits = s.filter { it.isDigit() }
        return when {
            s.startsWith("+") -> "+$digits"
            digits.length >= 11 && !digits.startsWith("0") -> "+$digits"
            else -> digits
        }
    }

    private fun looksLikePhone(cell: String): Boolean {
        val t = cell.trim()
        if (t.isEmpty() || t.any { it.isLetter() } || DATE.matches(t)) return false
        return t.count { it.isDigit() } in 6..15
    }

    fun parse(text: String): List<Lead> {
        val lines = text.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return emptyList()
        val sample = lines.take(10)
        val delim = listOf(';', ',', '\t').maxByOrNull { d -> sample.sumOf { l -> l.count { it == d } } } ?: ';'
        return parseRows(lines.map { split(it, delim) })
    }

    /** Excel workbook: the first sheet that yields any leads. */
    fun parseXlsx(bytes: ByteArray): List<Lead> =
        XlsxReader.sheets(bytes).asSequence().map { parseRows(it) }.firstOrNull { it.isNotEmpty() } ?: emptyList()

    /**
     * Finds the phone column by its header (title rows above the header are fine), and only
     * trusts a header whose column really holds numbers. Without a usable header the phone
     * column is the one that holds the most phone numbers, and the name is the first text column.
     */
    fun parseRows(allRows: List<List<String>>): List<Lead> {
        val rows = allRows.filter { r -> r.any { it.isNotBlank() } }
        if (rows.isEmpty()) return emptyList()
        val width = rows.maxOf { it.size }
        fun cell(r: List<String>, i: Int) = if (i >= 0) r.getOrNull(i)?.trim().orEmpty() else ""
        fun phoneCount(col: Int, from: Int) = rows.drop(from).count { looksLikePhone(cell(it, col)) }
        fun filledCount(col: Int, from: Int) = rows.drop(from).count { cell(it, col).isNotEmpty() }
        fun holdsPhones(col: Int, from: Int) = phoneCount(col, from).let { it > 0 && it * 2 >= filledCount(col, from) }
        // Keys are tried in priority order, so "Telefon" wins over e.g. "Kundennummer".
        fun find(header: List<String>, vararg keys: String): Int {
            for (key in keys) {
                val i = header.indexOfFirst { it.contains(key) }
                if (i >= 0) return i
            }
            return -1
        }
        val phoneKeys = arrayOf("telefon", "phone", "mobil", "handy", "tel", "nummer")

        var headerIdx = -1
        var header = emptyList<String>()
        var phoneCol = -1
        for (i in 0 until minOf(10, rows.size)) {
            val h = rows[i].map { it.trim().lowercase(Locale.ROOT) }
            val c = find(h, *phoneKeys)
            if (c >= 0 && holdsPhones(c, i + 1)) {
                headerIdx = i
                header = h
                phoneCol = c
                break
            }
        }
        if (phoneCol < 0) {
            phoneCol = (0 until width).maxByOrNull { phoneCount(it, 0) } ?: return emptyList()
            if (!holdsPhones(phoneCol, 0)) return emptyList()
            // The row right above the first number is most likely a header we did not recognize.
            headerIdx = rows.indexOfFirst { looksLikePhone(cell(it, phoneCol)) } - 1
            header = if (headerIdx >= 0) rows[headerIdx].map { it.trim().lowercase(Locale.ROOT) } else emptyList()
        }

        val companyCol = find(header, "firm", "company", "unternehmen", "betrieb", "organisation")
        val noteCol = find(header, "notiz", "note", "bemerkung", "kommentar", "info", "zusatz")
        val firstCol = find(header, "vorname", "first")
        val lastCol = find(header, "nachname", "last", "surname")
        val taken = setOf(phoneCol, companyCol, noteCol, firstCol, lastCol)
        var nameCol = header.indices.firstOrNull { i ->
            i !in taken && listOf("name", "kontakt", "ansprechpartner", "person").any { header[i].contains(it) }
        } ?: -1

        val data = rows.drop(headerIdx + 1)
        if (nameCol < 0 && firstCol < 0 && lastCol < 0) {
            // No name header: take the first column that mostly holds text.
            nameCol = (0 until width).firstOrNull { col ->
                col !in taken && data.count { r -> cell(r, col).any { it.isLetter() } } * 2 >= data.size
            } ?: -1
        }

        return data.mapNotNull { r ->
            if (!looksLikePhone(cell(r, phoneCol))) return@mapNotNull null
            val phone = cleanPhone(cell(r, phoneCol))
            val name = cell(r, nameCol).ifBlank {
                listOf(cell(r, firstCol), cell(r, lastCol)).filter { it.isNotBlank() }.joinToString(" ")
            }
            Lead(name = name, phone = phone, company = cell(r, companyCol), note = cell(r, noteCol))
        }
    }

    /** Semicolon-separated with BOM so German Excel opens it correctly. */
    fun export(leads: List<Lead>): String {
        val fmt = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMANY)
        val sb = StringBuilder("﻿")
        sb.append("Name;Telefon;Firma;Status;Versuche;Letzter Anruf;Notiz;Liste\r\n")
        for (l in leads) {
            val cells = listOf(
                l.name, l.phone, l.company, l.status.label, l.attempts.toString(),
                if (l.lastCall > 0) fmt.format(Date(l.lastCall)) else "", l.note, l.list,
            )
            sb.append(cells.joinToString(";") { escape(it) }).append("\r\n")
        }
        return sb.toString()
    }

    private fun escape(s: String) =
        if (s.any { it == ';' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    private fun split(line: String, d: Char): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (quoted && c == '"' && i + 1 < line.length && line[i + 1] == '"') {
                sb.append('"')
                i++
            } else if (c == '"') {
                quoted = !quoted
            } else if (c == d && !quoted) {
                out.add(sb.toString())
                sb.setLength(0)
            } else {
                sb.append(c)
            }
            i++
        }
        out.add(sb.toString())
        return out
    }
}
