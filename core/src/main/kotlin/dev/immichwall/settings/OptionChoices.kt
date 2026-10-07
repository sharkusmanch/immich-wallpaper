package dev.immichwall.settings

/** What the options screen offers for the numeric options; restoring a backup coerces to the same. */
object OptionChoices {
    const val CACHE_COUNT_MIN = 50
    const val CACHE_COUNT_MAX = 300
    const val CACHE_COUNT_STEP = 10

    /** Refresh intervals (hours) on offer. */
    val REFRESH_HOURS = listOf(3, 6, 12, 24)
    const val DEFAULT_REFRESH_HOURS = 6

    /** Rotation cadences (minutes) on offer; 0 = at every wake. */
    val ROTATION_MINUTES = listOf(0, 5, 60, 360, 1440)
    const val DEFAULT_ROTATION_MINUTES = 0
}
