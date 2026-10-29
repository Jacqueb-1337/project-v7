package app.morphe.manager.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectV7ReleaseAssetsTest {
    @Test
    fun `production channel excludes historical debug apks`() {
        assertTrue(isProjectV7ManagerApkFile("project-v7-patcher-1.33.3.apk", false))
        assertFalse(isProjectV7ManagerApkFile("project-v7-patcher-1.33.2-debug.apk", false))
        assertFalse(isProjectV7ManagerApkFile("project-v7-patcher-1.33.3-unsigned.apk", false))
        assertFalse(isProjectV7ManagerApkFile("morphe-manager-1.33.3.apk", false))
    }

    @Test
    fun `debug channel never accepts production signing identity`() {
        assertTrue(isProjectV7ManagerApkFile("project-v7-patcher-1.33.2-debug.apk", true))
        assertFalse(isProjectV7ManagerApkFile("project-v7-patcher-1.33.3.apk", true))
        assertFalse(isProjectV7ManagerApkFile("project-v7-patcher-1.33.3-unsigned.apk", true))
    }
}
