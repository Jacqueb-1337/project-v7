/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.viewmodel

import androidx.compose.ui.graphics.Color
import app.morphe.manager.domain.bundles.AppVersionStatus
import app.morphe.manager.domain.manager.HomeAppCategoryState
import app.morphe.manager.domain.manager.HomeAppCategoryViewMode
import app.morphe.manager.domain.manager.HomeAppSortMode
import app.morphe.manager.ui.model.HomeAppItem
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HomeCardCacheTest {
    private val dir = Files.createTempDirectory("morphe-home-cards").toFile()
    private val file = dir.resolve("home").resolve("cards.json")

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private val prefs = HomePrefs(
        hiddenPackages = emptySet(),
        customOrder = emptyList(),
        sourceOrders = emptyMap(),
        sortMode = HomeAppSortMode.entries.first(),
        categoryState = HomeAppCategoryState(emptyList(), emptyMap()),
        categoryViewMode = HomeAppCategoryViewMode.ALL_APPS,
        showCategoryViewSwitcher = false,
        expandedSourceGroups = emptySet()
    )

    private fun item(packageName: String, savedApk: File? = null) = HomeAppItem(
        id = packageName,
        packageName = packageName,
        displayName = "App $packageName",
        gradientColors = listOf(Color(0xFF112233), Color(0xFF445566)),
        installedApp = null,
        packageInfo = null,
        version = "1.0",
        isPinnedByDefault = false,
        isInstalledOnDevice = true,
        isDeleted = false,
        isInstallStateNotPatched = false,
        isInstallStateUnknown = false,
        isInstallStatePending = false,
        savedApkFile = savedApk,
        hasUpdate = true,
        versionStatus = AppVersionStatus("1.0", "2.0", isBehind = true),
        patchCount = 0,
        isClone = false,
        nameSuffix = "web"
    )

    private fun state(vararg items: HomeAppItem) = HomeAppState(
        visible = items.toList(),
        hidden = emptyList(),
        sortMode = prefs.sortMode,
        categoryState = prefs.categoryState,
        categoryViewMode = prefs.categoryViewMode,
        showCategoryViewSwitcher = prefs.showCategoryViewSwitcher,
        sourceGroups = listOf(HomeAppSourceGroup(0, "Morphe", setOf("a"), listOf("a"), false, null, null))
    )

    @Test
    fun `cards written by one run are read back by the next`() {
        val saved = dir.resolve("saved.apk").apply { writeText("apk") }
        val written = state(item("a", saved))
        HomeCardCache(file).write(written)

        val read = assertNotNull(HomeCardCache(file).read()).toState(emptyList(), prefs)

        assertEquals(written, read)
    }

    @Test
    fun `a cache cleared while the app runs is written again by the next build`() {
        val cache = HomeCardCache(file)
        val cards = state(item("a"))
        cache.write(cards)
        file.delete()

        cache.write(cards)

        assertTrue(file.exists())
    }

    @Test
    fun `a saved APK removed since is not offered from the cache`() {
        val saved = dir.resolve("saved.apk").apply { writeText("apk") }
        HomeCardCache(file).write(state(item("a", saved)))
        saved.delete()

        val read = assertNotNull(HomeCardCache(file).read()).toState(emptyList(), prefs)

        assertNull(read.visible.single().savedApkFile)
    }

    @Test
    fun `cards of another format are dropped`() {
        HomeCardCache(file).write(state(item("a")))
        file.writeText(file.readText().replace("\"format\":2", "\"format\":0"))

        assertNull(HomeCardCache(file).read())
    }
}
