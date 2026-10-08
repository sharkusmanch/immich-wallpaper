package dev.immichwall.source

/** A saved cycle and what to call it in a list of cycles; see [CycleLabels.of]. */
data class LabeledCycle(val cycle: SavedCycle, val label: String)

/**
 * How saved cycles are listed: their order, and a label for each that no other cycle in the
 * list has. Names alone do not tell cycles apart: a cycle is named after its source, Immich
 * allows two albums of one name, and a dropdown marks every row whose text is the chosen
 * one. Every screen that lists cycles, or looks a cycle's label up by id, takes both the
 * order and the labels from [of], so a row's position and its text always mean one cycle.
 */
object CycleLabels {

    /**
     * [cycles] by name without regard to case, same names in stored order. The first cycle
     * of a name is labelled with it; the next ones get " (2)", " (3)" and so on, stepping
     * over any number that would repeat another label (a cycle may really be named "X (2)").
     */
    fun of(cycles: List<SavedCycle>): List<LabeledCycle> {
        // Every real name is spoken for from the start, so no numbered label can take one.
        val taken = cycles.mapTo(HashSet()) { it.name }
        val seen = HashSet<String>()
        return cycles.sortedBy { it.name.lowercase() }.map { cycle ->
            if (seen.add(cycle.name)) return@map LabeledCycle(cycle, cycle.name)
            var number = 2
            while (!taken.add("${cycle.name} ($number)")) number++
            LabeledCycle(cycle, "${cycle.name} ($number)")
        }
    }
}
