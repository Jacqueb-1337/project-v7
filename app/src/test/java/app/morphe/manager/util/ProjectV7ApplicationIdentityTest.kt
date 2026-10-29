package app.morphe.manager.util

import app.morphe.manager.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Guard against accidentally publishing PV7 over the original Morphe Manager.
 * Keep the Kotlin namespace independent of the Android package/applicationId.
 */
class ProjectV7ApplicationIdentityTest {
    @Test
    fun `pv7 release and debug variants share an independent base package`() {
        val baseId = BuildConfig.APPLICATION_ID.removeSuffix(".debug")
        assertEquals("me.jacqueb.projectv7", baseId)
        assertNotEquals("app.morphe.manager", baseId)
    }
}
