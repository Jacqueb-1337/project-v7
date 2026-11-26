package app.morphe.manager.domain.pv7
import org.junit.Assert.*
import org.junit.Test
class Pv7GameCatalogSearchTest {
    private val best = Pv7SupportedApp("com.turner.bestparkintheuniverse", "Best Park in the Universe",
        recommendedVersion = "1.2.1", engine = "Cocos2d-x", experimental = true)
    private val cnr = Pv7SupportedApp("com.joydo.minestrikenew", "Cops N Robbers", engine = "Unity 4.6")
    private val apps = listOf(cnr, best, best)
    @Test fun searchAndSort() {
        assertEquals(listOf(best, cnr), Pv7GameCatalogSearch.search(apps, ""))
        assertEquals(listOf(best), Pv7GameCatalogSearch.search(apps, "  BEST   park "))
        assertEquals(listOf(cnr), Pv7GameCatalogSearch.search(apps, "joydo unity"))
        assertEquals(listOf(best), Pv7GameCatalogSearch.search(apps, "experimental 1.2.1"))
        assertTrue(Pv7GameCatalogSearch.search(apps, "missing").isEmpty())
    }
    @Test fun experimentalGamesAreOptIn() {
        assertEquals(listOf(cnr), Pv7GameCatalogSearch.visibleGames(apps, false))
        assertEquals(apps, Pv7GameCatalogSearch.visibleGames(apps, true))
        assertTrue(Pv7GameCatalogSearch.search(Pv7GameCatalogSearch.visibleGames(apps, false), "experimental").isEmpty())
    }
    @Test fun homeContainsOnlyOwnedCatalogGames() {
        val packages = setOf(best.packageName, cnr.packageName)
        assertFalse(Pv7GameCatalogSearch.showOnHome(best.packageName, packages, false, false))
        assertTrue(Pv7GameCatalogSearch.showOnHome(best.packageName, packages, true, false))
        assertTrue(Pv7GameCatalogSearch.showOnHome(best.packageName, packages, false, true))
        assertTrue(Pv7GameCatalogSearch.showOnHome("custom.app", packages, false, false))
    }
}
