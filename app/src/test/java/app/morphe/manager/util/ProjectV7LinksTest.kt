package app.morphe.manager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Prevent inherited upstream website links from becoming PV7 update destinations again. */
class ProjectV7LinksTest {
    @Test
    fun `patcher update and release links point to the PV7 repository`() {
        assertEquals("https://github.com/Jacqueb-1337/project-v7", MANAGER_REPO_URL)
        assertEquals("$MANAGER_REPO_URL/releases", MANAGER_RELEASES_URL)
        assertTrue(MANAGER_RELEASES_URL.startsWith("https://github.com/Jacqueb-1337/"))
        assertFalse(MANAGER_RELEASES_URL.contains("morphe.software"))
    }
}
