package de.leaddialer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/** Made-up data only: this repository is public. */
class ScheduleTest {

    // Friday 2026-10-16, 10:00
    private val friday = LocalDateTime.of(2026, 10, 16, 10, 0)

    @Test
    fun weeklyPicksNextMatchingDay() {
        val s = Schedule(name = "A", phone = "+43660", minuteOfDay = 9 * 60, days = ScheduleMath.parseDays("Mo, Mi")!!)
        assertEquals(LocalDateTime.of(2026, 10, 19, 9, 0), ScheduleMath.next(s, friday))
    }

    @Test
    fun sameDayLaterTimeStillCounts() {
        val s = Schedule(name = "A", phone = "+43660", minuteOfDay = 18 * 60 + 30, days = ScheduleMath.EVERY_DAY)
        assertEquals(LocalDateTime.of(2026, 10, 16, 18, 30), ScheduleMath.next(s, friday))
    }

    @Test
    fun exactlyNowIsNotNextAgain() {
        val s = Schedule(name = "A", phone = "+43660", minuteOfDay = 10 * 60, days = ScheduleMath.EVERY_DAY)
        assertEquals(LocalDateTime.of(2026, 10, 17, 10, 0), ScheduleMath.next(s, friday))
    }

    @Test
    fun oneTimeInThePastNeverFiresAgain() {
        val past = Schedule(name = "A", phone = "+43660", minuteOfDay = 9 * 60, days = 0, date = "2026-10-16")
        assertNull(ScheduleMath.next(past, friday))
        val future = past.copy(date = "2026-10-24")
        assertEquals(LocalDateTime.of(2026, 10, 24, 9, 0), ScheduleMath.next(future, friday))
    }

    @Test
    fun readsDaysTimesAndDates() {
        assertEquals(ScheduleMath.WEEKDAYS, ScheduleMath.parseDays("Mo-Fr"))
        assertEquals(ScheduleMath.WEEKEND, ScheduleMath.parseDays("Sa, So"))
        assertEquals(ScheduleMath.EVERY_DAY, ScheduleMath.parseDays("täglich"))
        assertEquals(0, ScheduleMath.parseDays(""))
        assertEquals(1 or 4 or 16, ScheduleMath.parseDays("Montag, Mittwoch und Freitag"))
        assertNull(ScheduleMath.parseDays("irgendwann"))
        assertEquals(9 * 60, ScheduleMath.parseTime("9:00"))
        assertEquals(9 * 60 + 30, ScheduleMath.parseTime("09.30 Uhr"))
        assertEquals(9 * 60, ScheduleMath.parseTime("0.375"))
        assertNull(ScheduleMath.parseTime("25:00"))
        val today = LocalDate.of(2026, 10, 16)
        assertEquals("2026-10-24", ScheduleMath.parseDate("24.10.2026", today))
        assertEquals("2027-01-03", ScheduleMath.parseDate("3.1.", today))
    }

    @Test
    fun readsPlanTableAndReportsBadRows() {
        val rows = listOf(
            listOf("Name", "Telefon", "Tage", "Uhrzeit", "Datum"),
            listOf("Max Beispiel", "+43 660 1234567", "Mo, Mi", "9:00", ""),
            listOf("Eva Probe", "0664 7654321", "", "18:30", "24.10.2026"),
            listOf("Kaputt", "0664 7654321", "", "18:30", ""),
        )
        val (plan, errors) = ScheduleMath.parsePlan(rows, LocalDate.of(2026, 10, 16))
        assertEquals(listOf("Max Beispiel", "Eva Probe"), plan.map { it.name })
        assertEquals("+436601234567", plan[0].phone)
        assertEquals("2026-10-24", plan[1].date)
        assertEquals(1, errors.size)
    }
}
