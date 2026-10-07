package dev.immichwall.schedule

import java.time.LocalDate
import java.time.MonthDay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ScheduleResolverTest {
    private val known = setOf("default", "summer", "christmas", "spring", "fall", "special", "bday")

    private fun entry(name: String, cycle: String, start: String, end: String) =
        ScheduleEntry(id = name, name = name, cycleId = cycle, start = start, end = end)

    private fun schedule(vararg entries: ScheduleEntry, default: String = "default") =
        Schedule(enabled = true, defaultCycleId = default, entries = entries.toList())

    private fun cycleOn(s: Schedule, y: Int, m: Int, d: Int) =
        ScheduleResolver.resolve(s, LocalDate.of(y, m, d), known)?.cycleId

    // ---- ported from immich-kiosk-scheduler internal/scheduler/scheduler_test.go ----

    @Test fun `parses month-day`() {
        assertEquals(MonthDay.of(11, 15), ScheduleResolver.parseMonthDay("11-15"))
        assertEquals(MonthDay.of(1, 1), ScheduleResolver.parseMonthDay("01-01"))
        assertEquals(MonthDay.of(12, 31), ScheduleResolver.parseMonthDay("12-31"))
        assertNull(ScheduleResolver.parseMonthDay("2024-11-15"))
        assertNull(ScheduleResolver.parseMonthDay("13-01"))
        assertNull(ScheduleResolver.parseMonthDay("01-32"))
    }

    @Test fun `simple range is inclusive at both ends`() {
        val s = schedule(entry("summer", "summer", "06-21", "09-21"))
        assertEquals("default", cycleOn(s, 2024, 6, 20))
        assertEquals("summer", cycleOn(s, 2024, 6, 21))
        assertEquals("summer", cycleOn(s, 2024, 7, 15))
        assertEquals("summer", cycleOn(s, 2024, 9, 21))
        assertEquals("default", cycleOn(s, 2024, 9, 22))
    }

    @Test fun `range wraps the year`() {
        val s = schedule(entry("christmas", "christmas", "11-15", "01-01"))
        assertEquals("default", cycleOn(s, 2024, 11, 14))
        assertEquals("christmas", cycleOn(s, 2024, 11, 15))
        assertEquals("christmas", cycleOn(s, 2024, 12, 25))
        assertEquals("christmas", cycleOn(s, 2025, 1, 1))
        assertEquals("default", cycleOn(s, 2025, 1, 2))
    }

    @Test fun `multiple entries`() {
        val s = schedule(
            entry("christmas", "christmas", "11-15", "01-01"),
            entry("spring", "spring", "03-20", "06-20"),
            entry("summer", "summer", "06-21", "09-21"),
            entry("fall", "fall", "09-22", "11-14"),
        )
        assertEquals("default", cycleOn(s, 2024, 2, 15))
        assertEquals("spring", cycleOn(s, 2024, 4, 15))
        assertEquals("summer", cycleOn(s, 2024, 7, 15))
        assertEquals("fall", cycleOn(s, 2024, 10, 15))
        assertEquals("christmas", cycleOn(s, 2024, 12, 25))
    }

    @Test fun `first match wins`() {
        val s = schedule(
            entry("special", "special", "12-20", "12-26"),
            entry("christmas", "christmas", "11-15", "01-01"),
        )
        assertEquals("special", cycleOn(s, 2024, 12, 25))
        assertEquals("christmas", cycleOn(s, 2024, 11, 20))
    }

    @Test fun `winning entry is reported and default has none`() {
        val s = schedule(entry("summer", "summer", "06-21", "09-21"))
        assertEquals(Resolution("summer", "summer"), ScheduleResolver.resolve(s, LocalDate.of(2024, 7, 15), known))
        assertEquals(Resolution("default", null), ScheduleResolver.resolve(s, LocalDate.of(2024, 1, 15), known))
    }

    @Test fun `empty schedule gives the default`() {
        assertEquals("default", cycleOn(schedule(), 2024, 5, 5))
    }

    // ---- new behaviour ----

    @Test fun `single-day entry matches only that day`() {
        val s = schedule(entry("bday", "bday", "04-14", "04-14"))
        assertEquals("default", cycleOn(s, 2025, 4, 13))
        assertEquals("bday", cycleOn(s, 2025, 4, 14))
        assertEquals("default", cycleOn(s, 2025, 4, 15))
    }

    @Test fun `entry whose cycle was deleted is skipped`() {
        val s = schedule(
            entry("gone", "deleted-cycle", "07-01", "07-31"),
            entry("summer", "summer", "06-21", "09-21"),
        )
        assertEquals("summer", cycleOn(s, 2024, 7, 15))
    }

    @Test fun `no match and no usable default resolves to nothing`() {
        assertNull(ScheduleResolver.resolve(schedule(default = ""), LocalDate.of(2024, 5, 5), known))
        assertNull(ScheduleResolver.resolve(schedule(default = "deleted-cycle"), LocalDate.of(2024, 5, 5), known))
    }

    @Test fun `entry with unparseable dates never matches`() {
        val s = schedule(entry("bad", "summer", "02-30", "9-1"))
        assertEquals("default", cycleOn(s, 2024, 3, 1))
    }

    @Test fun `range ending feb 29 ends on feb 28 in a non-leap year`() {
        val s = schedule(entry("winter", "special", "02-01", "02-29"))
        assertEquals("special", cycleOn(s, 2024, 2, 29))
        assertEquals("default", cycleOn(s, 2024, 3, 1))
        assertEquals("special", cycleOn(s, 2025, 2, 28))
        assertEquals("default", cycleOn(s, 2025, 3, 1))
    }

    @Test fun `single-day feb 29 entry matches only in leap years`() {
        val s = schedule(entry("leap", "special", "02-29", "02-29"))
        assertEquals("special", cycleOn(s, 2024, 2, 29))
        assertEquals("default", cycleOn(s, 2025, 2, 28))
        assertEquals("default", cycleOn(s, 2025, 3, 1))
    }

    @Test fun `next change finds the following boundary across the year end`() {
        val s = schedule(entry("christmas", "christmas", "11-26", "12-31"), entry("fall", "fall", "08-31", "11-25"))
        assertEquals(
            LocalDate.of(2026, 11, 26) to Resolution("christmas", "christmas"),
            ScheduleResolver.nextChange(s, LocalDate.of(2026, 10, 6), known),
        )
        assertEquals(
            LocalDate.of(2027, 1, 1) to Resolution("default", null),
            ScheduleResolver.nextChange(s, LocalDate.of(2026, 12, 25), known),
        )
    }

    @Test fun `next change is null when the answer never changes`() {
        assertNull(ScheduleResolver.nextChange(schedule(), LocalDate.of(2026, 10, 6), known))
    }

    @Test fun `entry fully covered by an earlier one is shadowed`() {
        val s = schedule(
            entry("christmas", "christmas", "11-15", "01-01"),
            entry("special", "special", "12-20", "12-26"),
            entry("summer", "summer", "06-21", "09-21"),
        )
        assertEquals(setOf("special"), ScheduleResolver.shadowedEntryIds(s, known))
    }

    @Test fun `partly covered entry is not shadowed`() {
        val s = schedule(
            entry("special", "special", "12-20", "12-26"),
            entry("christmas", "christmas", "11-15", "01-01"),
        )
        assertEquals(emptySet(), ScheduleResolver.shadowedEntryIds(s, known))
    }
}
