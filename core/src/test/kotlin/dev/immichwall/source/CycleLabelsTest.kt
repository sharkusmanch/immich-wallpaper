package dev.immichwall.source

import kotlin.test.Test
import kotlin.test.assertEquals

class CycleLabelsTest {
    private fun cycle(id: String, name: String) = SavedCycle(id = id, name = name, spec = SourceSpec.Favorites)

    private fun labels(vararg cycles: SavedCycle): List<Pair<String, String>> =
        CycleLabels.of(cycles.toList()).map { it.cycle.id to it.label }

    @Test fun `unique names are their own labels, sorted without regard to case`() {
        assertEquals(
            listOf("c2" to "album: Trips", "c3" to "Favorites", "c1" to "Search: beach"),
            labels(cycle("c1", "Search: beach"), cycle("c2", "album: Trips"), cycle("c3", "Favorites")),
        )
    }

    @Test fun `the order is the one the screens used before`() {
        val cycles = listOf("b", "A", "a", "C", "B", "a").mapIndexed { i, name -> cycle("c$i", name) }
        assertEquals(cycles.sortedBy { it.name.lowercase() }, CycleLabels.of(cycles).map { it.cycle })
    }

    @Test fun `two cycles with one name are told apart in stored order`() {
        assertEquals(
            listOf("c2" to "Album: Trips", "c1" to "Album: Trips (2)", "c3" to "Favorites"),
            labels(cycle("c3", "Favorites"), cycle("c2", "Album: Trips"), cycle("c1", "Album: Trips")),
        )
    }

    @Test fun `three cycles with one name are numbered two and three`() {
        assertEquals(
            listOf("c1" to "X", "c2" to "X (2)", "c3" to "X (3)"),
            labels(cycle("c1", "X"), cycle("c2", "X"), cycle("c3", "X")),
        )
    }

    @Test fun `a cycle really named like a numbered one keeps its name and the numbering steps over it`() {
        assertEquals(
            listOf("c1" to "X", "c2" to "X (3)", "c3" to "X (2)"),
            labels(cycle("c1", "X"), cycle("c2", "X"), cycle("c3", "X (2)")),
        )
        assertEquals(
            listOf("c3" to "X", "c1" to "X (3)", "c2" to "X (2)"),
            labels(cycle("c2", "X (2)"), cycle("c3", "X"), cycle("c1", "X")),
        )
    }

    @Test fun `names that differ only in case are different names`() {
        assertEquals(listOf("c1" to "x", "c2" to "X"), labels(cycle("c1", "x"), cycle("c2", "X")))
    }

    @Test fun `labels are distinct and every cycle is listed once, however the names collide`() {
        val names = listOf("X", "X", "X (2)", "X (2)", "X (3)", "X", "X (2) (2)", "", "", "x", "X (4)", "X")
        val cycles = names.mapIndexed { i, name -> cycle("c$i", name) }
        val labeled = CycleLabels.of(cycles)
        assertEquals(cycles.size, labeled.map { it.label }.toSet().size, labeled.map { it.label }.toString())
        assertEquals(cycles.map { it.id }.toSet(), labeled.map { it.cycle.id }.toSet())
        assertEquals(cycles.size, labeled.size)
        // The first cycle of each name, in stored order, is the one that keeps it.
        for (name in names.toSet()) {
            assertEquals(cycles.first { it.name == name }.id, labeled.first { it.label == name }.cycle.id, name)
        }
    }

    @Test fun `no cycles, no labels`() {
        assertEquals(emptyList(), CycleLabels.of(emptyList()))
    }
}
