package de.leaddialer

import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CsvIO {

    /** German Excel often saves as Windows-1252 instead of UTF-8, so fall back when UTF-8 fails. */
    fun decode(bytes: ByteArray): String {
        var text = String(bytes, Charsets.UTF_8)
        if (text.contains('�')) text = String(bytes, Charset.forName("windows-1252"))
        return text.removePrefix("﻿")
    }

    fun cleanPhone(raw: String): String {
        val s = raw.replace("(0)", "").trim()
        val digits = s.filter { it.isDigit() }
        return if (s.startsWith("+")) "+$digits" else digits
    }

    fun parse(text: String): List<Lead> {
        val lines = text.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return emptyList()

        val delim = listOf(';', ',', '\t').maxByOrNull { d -> lines[0].count { it == d } } ?: ';'
        val rows = lines.map { split(it, delim) }
        val header = rows[0].map { it.trim().lowercase(Locale.ROOT) }
        // Keys are tried in priority order, so "Telefon" wins over e.g. "Kundennummer".
        fun find(vararg keys: String): Int {
            for (key in keys) {
                val i = header.indexOfFirst { it.contains(key) }
                if (i >= 0) return i
            }
            return -1
        }

        var phoneCol = find("telefon", "phone", "mobil", "handy", "tel", "nummer")
        val hasHeader = phoneCol >= 0
        var nameCol = -1
        var companyCol = -1
        var noteCol = -1
        var firstCol = -1
        var lastCol = -1

        if (hasHeader) {
            companyCol = find("firm", "company", "unternehmen", "betrieb", "organisation")
            noteCol = find("notiz", "note", "bemerkung", "kommentar", "info")
            firstCol = find("vorname", "first")
            lastCol = find("nachname", "last", "surname")
            val taken = setOf(phoneCol, companyCol, noteCol, firstCol, lastCol)
            nameCol = header.indices.firstOrNull { i ->
                i !in taken && listOf("name", "kontakt", "ansprechpartner", "person").any { header[i].contains(it) }
            } ?: -1
        } else {
            // No header: guess the phone column from the first row, name is the first other column.
            phoneCol = rows[0].indexOfFirst { cell -> cleanPhone(cell).count { it.isDigit() } >= 6 }
            if (phoneCol < 0) return emptyList()
            nameCol = if (phoneCol == 0) 1 else 0
            companyCol = rows[0].indices.firstOrNull { it != phoneCol && it != nameCol } ?: -1
        }

        val data = if (hasHeader) rows.drop(1) else rows
        return data.mapNotNull { r ->
            fun at(i: Int) = if (i >= 0) r.getOrNull(i)?.trim().orEmpty() else ""
            val phone = cleanPhone(at(phoneCol))
            if (phone.count { it.isDigit() } < 5) return@mapNotNull null
            val name = at(nameCol).ifBlank {
                listOf(at(firstCol), at(lastCol)).filter { it.isNotBlank() }.joinToString(" ")
            }
            Lead(name = name, phone = phone, company = at(companyCol), note = at(noteCol))
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
