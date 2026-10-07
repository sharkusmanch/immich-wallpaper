package dev.immichwall.schedule

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SchedulePlanTest {
    private val known = setOf("default", "spring", "fall", "christmas", "bday", "manual")

    private val schedule = Schedule(
        enabled = true,
        defaultCycleId = "default",
        entries = listOf(
            ScheduleEntry("e-bday", "birthday", "bday", "04-14", "04-14"),
            ScheduleEntry("e-xmas", "christmas", "christmas", "11-26", "12-31"),
            ScheduleEntry("e-fall", "fall", "fall", "08-31", "11-25"),
            ScheduleEntry("e-spring", "spring", "spring", "03-20", "05-31"),
        ),
    )

    private fun plan(date: LocalDate, override: ScheduleOverride? = null, current: String = "default", s: Schedule = schedule) =
        SchedulePlan.compute(s, override, current, date, known)

    @Test fun `mid-season keeps one cycle`() {
        val p = plan(LocalDate.of(2026, 10, 6))
        assertEquals("fall", p.activeCycleId)
        assertEquals(listOf("fall"), p.retainedCycleIds)
        assertFalse(p.overrideActive)
    }

    @Test fun `two days before a boundary the next cycle is retained for prefetch`() {
        assertEquals(listOf("fall"), plan(LocalDate.of(2026, 11, 23)).retainedCycleIds)
        assertEquals(listOf("fall", "christmas"), plan(LocalDate.of(2026, 11, 24)).retainedCycleIds)
        assertEquals(listOf("christmas"), plan(LocalDate.of(2026, 11, 26)).retainedCycleIds)
    }

    @Test fun `one-day entry keeps the surrounding cycle cached`() {
        assertEquals(listOf("spring", "bday"), plan(LocalDate.of(2026, 4, 13)).retainedCycleIds)
        val day = plan(LocalDate.of(2026, 4, 14))
        assertEquals("bday", day.activeCycleId)
        assertEquals(listOf("bday", "spring"), day.retainedCycleIds)
        assertEquals(listOf("spring"), plan(LocalDate.of(2026, 4, 15)).retainedCycleIds)
    }

    @Test fun `manual override holds while the schedule's answer is unchanged`() {
        val override = ScheduleOverride(cycleId = "manual", scheduledCycleId = "fall")
        val p = plan(LocalDate.of(2026, 10, 6), override)
        assertEquals("manual", p.activeCycleId)
        assertTrue(p.overrideActive)
        assertFalse(p.clearOverride)
        assertEquals(listOf("manual", "fall"), p.retainedCycleIds)
    }

    @Test fun `the scheduled cycle stays cached during an override right up to the boundary`() {
        val override = ScheduleOverride(cycleId = "manual", scheduledCycleId = "fall")
        assertEquals(listOf("manual", "fall", "christmas"), plan(LocalDate.of(2026, 11, 25), override).retainedCycleIds)
    }

    @Test fun `manual override ends when the schedule moves on`() {
        val override = ScheduleOverride(cycleId = "manual", scheduledCycleId = "fall")
        val p = plan(LocalDate.of(2026, 11, 26), override, current = "manual")
        assertEquals("christmas", p.activeCycleId)
        assertFalse(p.overrideActive)
        assertTrue(p.clearOverride)
    }

    @Test fun `override pointing at a deleted cycle is dropped`() {
        val p = plan(LocalDate.of(2026, 10, 6), ScheduleOverride("deleted", "fall"))
        assertEquals("fall", p.activeCycleId)
        assertTrue(p.clearOverride)
    }

    @Test fun `schedule off leaves the current cycle alone and drops any override`() {
        val off = schedule.copy(enabled = false)
        val p = plan(LocalDate.of(2026, 12, 25), ScheduleOverride("manual", "fall"), current = "spring", s = off)
        assertEquals("spring", p.activeCycleId)
        assertEquals(listOf("spring"), p.retainedCycleIds)
        assertNull(p.resolution)
        assertTrue(p.clearOverride)
    }

    @Test fun `schedule with no answer keeps the current cycle`() {
        val empty = Schedule(enabled = true, defaultCycleId = "", entries = emptyList())
        val p = plan(LocalDate.of(2026, 10, 6), current = "spring", s = empty)
        assertEquals("spring", p.activeCycleId)
        assertEquals(listOf("spring"), p.retainedCycleIds)
    }

    @Test fun `current cycle that no longer exists yields no active cycle when the schedule is off`() {
        val p = plan(LocalDate.of(2026, 10, 6), current = "deleted", s = schedule.copy(enabled = false))
        assertNull(p.activeCycleId)
        assertEquals(emptyList(), p.retainedCycleIds)
    }

    @Test fun `a phone that was off across several boundaries lands on today's cycle`() {
        val p = plan(LocalDate.of(2026, 12, 27), current = "spring")
        assertEquals("christmas", p.activeCycleId)
        assertEquals(listOf("christmas"), p.retainedCycleIds)
    }

    @Test fun `the date moving backwards simply gives that date's answer`() {
        assertEquals("christmas", plan(LocalDate.of(2026, 11, 26), current = "fall").activeCycleId)
        assertEquals("fall", plan(LocalDate.of(2026, 11, 25), current = "christmas").activeCycleId)
    }

    @Test fun `picking the cycle the schedule already wants stores no override`() {
        assertNull(SchedulePlan.overrideFor(schedule, "fall", LocalDate.of(2026, 10, 6), known))
    }

    @Test fun `picking another cycle records what the schedule said at that moment`() {
        assertEquals(
            ScheduleOverride("manual", "fall"),
            SchedulePlan.overrideFor(schedule, "manual", LocalDate.of(2026, 10, 6), known),
        )
    }

    @Test fun `picking a cycle with the schedule off stores no override`() {
        assertNull(SchedulePlan.overrideFor(schedule.copy(enabled = false), "manual", LocalDate.of(2026, 10, 6), known))
    }

    @Test fun `override taken while the schedule had no answer holds until it has one`() {
        val empty = Schedule(enabled = true, defaultCycleId = "", entries = listOf(ScheduleEntry("e-xmas", "christmas", "christmas", "11-26", "12-31")))
        val o = SchedulePlan.overrideFor(empty, "manual", LocalDate.of(2026, 10, 6), known)
        assertEquals(ScheduleOverride("manual", ""), o)
        assertEquals("manual", plan(LocalDate.of(2026, 11, 25), o, current = "manual", s = empty).activeCycleId)
        assertEquals("christmas", plan(LocalDate.of(2026, 11, 26), o, current = "manual", s = empty).activeCycleId)
    }
}
