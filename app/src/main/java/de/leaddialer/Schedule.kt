package de.leaddialer

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** A planned call. [days] is a weekday bit mask (bit 0 = Monday ... bit 6 = Sunday); 0 means once on [date]. */
data class Schedule(
    var id: Long = 0,
    var name: String,
    var phone: String,
    var minuteOfDay: Int,
    var days: Int = 0,
    /** yyyy-MM-dd, only for one-time calls. */
    var date: String? = null,
    var enabled: Boolean = true,
    /** Planned time (epoch ms) of the last occurrence that was handled, so nothing fires twice. */
    var lastFired: Long = 0,
    /** Came from the linked plan file (replaced on every sync) rather than entered in the app. */
    var fromFile: Boolean = false,
) {
    /** Identity across file syncs, so enabled state and lastFired survive a reload. */
    val key get() = "$phone|$minuteOfDay|$days|${date.orEmpty()}"
}

/** Pure scheduling logic, no Android types, so it is unit-tested. */
object ScheduleMath {
    const val EVERY_DAY = 127
    const val WEEKDAYS = 31
    const val WEEKEND = 96
    private val SHORT = listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So")
    private val DAY_KEYS = listOf("mo", "di", "mi", "do", "fr", "sa", "so")

    private fun bit(d: DayOfWeek) = 1 shl (d.value - 1)

    /** The first occurrence strictly after [after], or null if the call never happens again. */
    fun next(s: Schedule, after: LocalDateTime): LocalDateTime? {
        val h = s.minuteOfDay / 60
        val m = s.minuteOfDay % 60
        if (s.days == 0) {
            val d = s.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
            val t = d.atTime(h, m)
            return if (t.isAfter(after)) t else null
        }
        for (i in 0..7) {
            val d = after.toLocalDate().plusDays(i.toLong())
            if (s.days and bit(d.dayOfWeek) == 0) continue
            val t = d.atTime(h, m)
            if (t.isAfter(after)) return t
        }
        return null
    }

    fun timeLabel(minute: Int) = String.format(Locale.ROOT, "%02d:%02d", minute / 60, minute % 60)

    fun daysLabel(days: Int) = when (days) {
        0 -> "einmalig"
        EVERY_DAY -> "täglich"
        WEEKDAYS -> "Mo–Fr"
        WEEKEND -> "Sa, So"
        else -> SHORT.indices.filter { days and (1 shl it) != 0 }.joinToString(", ") { SHORT[it] }
    }

    fun describe(s: Schedule): String {
        if (s.days != 0) return daysLabel(s.days) + " · " + timeLabel(s.minuteOfDay)
        val date = s.date?.let { runCatching { LocalDate.parse(it).format(DateTimeFormatter.ofPattern("dd.MM.yyyy")) }.getOrNull() } ?: "?"
        return "einmalig $date · " + timeLabel(s.minuteOfDay)
    }

    fun whenLabel(t: LocalDateTime): String =
        SHORT[t.dayOfWeek.value - 1] + " " + t.format(DateTimeFormatter.ofPattern("dd.MM. HH:mm"))

    /** "9:00", "09.00", "9 Uhr", "9", or an Excel time value like 0.375. */
    fun parseTime(text: String): Int? {
        val t = text.trim().lowercase(Locale.ROOT).removeSuffix("uhr").trim()
        t.toDoubleOrNull()?.let { v -> if (v > 0 && v < 1) return (v * 1440).roundToInt() % 1440 }
        val m = Regex("""^(\d{1,2})(?:[:.](\d{2}))?(?::\d{2})?$""").find(t) ?: return null
        val h = m.groupValues[1].toInt()
        val mi = m.groupValues[2].ifEmpty { "0" }.toInt()
        return if (h in 0..23 && mi in 0..59) h * 60 + mi else null
    }

    /** "Mo, Mi, Fr", "Mo-Fr", "täglich", "werktags", "Wochenende"; empty or "einmalig" = 0. Null if unreadable. */
    fun parseDays(text: String): Int? {
        val t = text.trim().lowercase(Locale.ROOT)
        when (t) {
            "", "einmalig", "einmal" -> return 0
            "täglich", "taeglich", "jeden tag", "daily", "alle" -> return EVERY_DAY
            "werktags", "wochentags" -> return WEEKDAYS
            "wochenende" -> return WEEKEND
        }
        var mask = 0
        val parts = t.split(',', ';', '/', ' ', '+', '&').map { it.trim() }.filter { it.isNotEmpty() && it != "und" }
        for (part in parts) {
            val range = part.split('-', '–')
            if (range.size == 2) {
                val a = dayIndex(range[0]) ?: return null
                val b = dayIndex(range[1]) ?: return null
                var i = a
                while (true) {
                    mask = mask or (1 shl i)
                    if (i == b) break
                    i = (i + 1) % 7
                }
            } else {
                mask = mask or (1 shl (dayIndex(part) ?: return null))
            }
        }
        return mask.takeIf { it != 0 }
    }

    private fun dayIndex(s: String): Int? = DAY_KEYS.indexOf(s.trim().take(2)).takeIf { it >= 0 }

    /** "24.10.2026", "24.10.26", "24.10." (next such day), "2026-10-24" or an Excel day number. */
    fun parseDate(text: String, today: LocalDate): String? {
        val t = text.trim()
        if (t.isEmpty()) return null
        runCatching { return LocalDate.parse(t).toString() }
        t.toDoubleOrNull()?.let { v ->
            if (v in 30000.0..80000.0) return LocalDate.of(1899, 12, 30).plusDays(v.toLong()).toString()
        }
        val m = Regex("""^(\d{1,2})\.(\d{1,2})\.(\d{2,4})?$""").find(t) ?: return null
        val yearText = m.groupValues[3]
        val year = if (yearText.isEmpty()) today.year else yearText.toInt().let { if (it < 100) 2000 + it else it }
        var date = runCatching { LocalDate.of(year, m.groupValues[2].toInt(), m.groupValues[1].toInt()) }.getOrNull() ?: return null
        if (yearText.isEmpty() && date.isBefore(today)) date = date.plusYears(1)
        return date.toString()
    }

    /**
     * Reads a plan table: Name, Telefon, Tage, Uhrzeit, Datum (header names are matched loosely).
     * Returns the schedules plus one message per row that could not be read.
     */
    fun parsePlan(allRows: List<List<String>>, today: LocalDate): Pair<List<Schedule>, List<String>> {
        val rows = allRows.filter { r -> r.any { it.isNotBlank() } }
        fun find(header: List<String>, vararg keys: String): Int {
            for (key in keys) {
                val i = header.indexOfFirst { it.contains(key) }
                if (i >= 0) return i
            }
            return -1
        }
        var headerIdx = -1
        var header = emptyList<String>()
        for (i in 0 until minOf(10, rows.size)) {
            val h = rows[i].map { it.trim().lowercase(Locale.ROOT) }
            if (find(h, "uhrzeit", "zeit", "time") >= 0) {
                headerIdx = i
                header = h
                break
            }
        }
        if (headerIdx < 0) return emptyList<Schedule>() to listOf("Keine Spalte \"Uhrzeit\" gefunden.")
        val nameCol = find(header, "name")
        val phoneCol = find(header, "telefon", "phone", "handy", "mobil", "nummer", "tel")
        val daysCol = find(header, "tage", "wochentag", "wiederholung", "tag")
        val timeCol = find(header, "uhrzeit", "zeit", "time")
        val dateCol = find(header, "datum", "date")
        if (phoneCol < 0) return emptyList<Schedule>() to listOf("Keine Spalte \"Telefon\" gefunden.")

        val out = ArrayList<Schedule>()
        val errors = ArrayList<String>()
        rows.drop(headerIdx + 1).forEachIndexed { i, r ->
            fun cell(c: Int) = if (c >= 0) r.getOrNull(c)?.trim().orEmpty() else ""
            val line = headerIdx + i + 2
            val name = cell(nameCol)
            val label = name.ifBlank { "Zeile $line" }
            val phone = CsvIO.cleanPhone(cell(phoneCol))
            if (phone.count { it.isDigit() } < 5) { errors.add("$label: keine gültige Nummer"); return@forEachIndexed }
            val minute = parseTime(cell(timeCol)) ?: run { errors.add("$label: Uhrzeit \"${cell(timeCol)}\" unlesbar"); return@forEachIndexed }
            val days = parseDays(cell(daysCol)) ?: run { errors.add("$label: Tage \"${cell(daysCol)}\" unlesbar"); return@forEachIndexed }
            val date = if (days == 0) {
                parseDate(cell(dateCol), today) ?: run { errors.add("$label: einmalig, aber kein gültiges Datum"); return@forEachIndexed }
            } else null
            out.add(Schedule(name = name, phone = phone, minuteOfDay = minute, days = days, date = date, fromFile = true))
        }
        return out to errors
    }
}
