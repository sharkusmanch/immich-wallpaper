package dev.immichwall.backup

import kotlin.test.Test
import kotlin.test.assertEquals

class RestoreFollowUpTest {
    private fun startsSyncing(configured: Boolean, applied: Boolean?, syncsStopped: Boolean) =
        BackupRestore.startsSyncing(configured, applied, syncsStopped)

    @Test
    fun `a restore that replaced the cycles starts syncing`() {
        for (stopped in listOf(true, false)) {
            assertEquals(true, startsSyncing(configured = true, applied = true, syncsStopped = stopped))
        }
    }

    @Test
    fun `nothing applied and nothing stopped starts nothing`() {
        assertEquals(false, startsSyncing(configured = true, applied = false, syncsStopped = false))
    }

    @Test
    fun `syncing that was stopped is started again whatever the apply came to`() {
        for (applied in listOf(true, false, null)) {
            assertEquals(true, startsSyncing(configured = true, applied = applied, syncsStopped = true))
        }
    }

    @Test
    fun `an apply that threw may have replaced the cycles, so it starts syncing`() {
        assertEquals(true, startsSyncing(configured = true, applied = null, syncsStopped = false))
    }

    @Test
    fun `nothing starts before setup is finished`() {
        for (applied in listOf(true, false, null)) for (stopped in listOf(true, false)) {
            assertEquals(false, startsSyncing(configured = false, applied = applied, syncsStopped = stopped))
        }
    }
}
