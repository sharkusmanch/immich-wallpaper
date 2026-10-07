package dev.immichwall.schedule

import kotlin.test.Test
import kotlin.test.assertEquals

class ScheduleEntryRowTest {
    private fun entry(name: String, cycle: String = "c1", start: String = "10-07", end: String = "09-07") =
        ScheduleEntry(id = "e", name = name, cycleId = cycle, start = start, end = end)

    private val missing = "deleted cycle"

    @Test fun `named entry shows its name and the cycle line`() {
        val row = ScheduleEntryRow.of(entry("Winter holidays"), "Album: Christmas", missing)
        assertEquals(ScheduleEntryRow("Winter holidays", "Album: Christmas", singleDay = false), row)
    }

    @Test fun `unnamed entry titles itself with the cycle and has no cycle line`() {
        val row = ScheduleEntryRow.of(entry(""), "Album: Christmas", missing)
        assertEquals(ScheduleEntryRow("Album: Christmas", null, singleDay = false), row)
    }

    @Test fun `blank name counts as unnamed`() {
        val row = ScheduleEntryRow.of(entry("  "), "Album: Christmas", missing)
        assertEquals(ScheduleEntryRow("Album: Christmas", null, singleDay = false), row)
    }

    @Test fun `named entry with a missing cycle shows the missing text on the cycle line`() {
        val row = ScheduleEntryRow.of(entry("Winter holidays"), null, missing)
        assertEquals(ScheduleEntryRow("Winter holidays", missing, singleDay = false), row)
    }

    @Test fun `unnamed entry with a missing cycle shows the missing text as its title`() {
        val row = ScheduleEntryRow.of(entry(""), null, missing)
        assertEquals(ScheduleEntryRow(missing, null, singleDay = false), row)
    }

    @Test fun `start equal to end is one day`() {
        val row = ScheduleEntryRow.of(entry("", start = "10-07", end = "10-07"), "A", missing)
        assertEquals(true, row.singleDay)
    }

    @Test fun `year-wrapping range is not one day`() {
        val row = ScheduleEntryRow.of(entry("", start = "11-26", end = "01-01"), "A", missing)
        assertEquals(false, row.singleDay)
    }

    @Test fun `a full-year range ending the day before it starts is not one day`() {
        val row = ScheduleEntryRow.of(entry("", start = "10-07", end = "10-06"), "A", missing)
        assertEquals(false, row.singleDay)
    }
}
