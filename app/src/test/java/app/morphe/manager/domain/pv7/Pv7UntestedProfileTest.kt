package app.morphe.manager.domain.pv7

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Pv7UntestedProfileTest {
    @Test fun manuallySelectedUnlistedArmv7GameCanUseGenericModules() {
        val core = Pv7ModuleManifest(
            id = "core-compat",
            name = "Core",
            version = "1.0",
            kind = Pv7ModuleKind.GENERAL,
            description = "General ARMv7",
            targets = Pv7TargetSelector(abis = listOf("armeabi-v7a")),
            suggestedWhenMatched = true
        )
        val unity = Pv7ModuleManifest(
            id = "unity-4.6-compat",
            name = "Unity",
            version = "1.0",
            kind = Pv7ModuleKind.GENERAL,
            description = "Experimental Unity",
            targets = Pv7TargetSelector(
                abis = listOf("armeabi-v7a"),
                engines = listOf("unity"),
                engineVersions = listOf("4.6.*")
            ),
            dependencies = listOf("core-compat"),
            suggestedWhenMatched = true
        )
        val cnrOnly = Pv7ModuleManifest(
            id = "cnr-compat",
            name = "CNR only",
            version = "1.0",
            kind = Pv7ModuleKind.GAME,
            description = "CNR",
            targets = Pv7TargetSelector(packageIds = listOf("com.joydo.minestrikenew")),
            dependencies = listOf("unity-4.6-compat")
        )
        val untestedGame = Pv7CompatibilityProfile(
            id = "regular-show-1.2",
            packageId = "com.turner.bestparkintheuniverse",
            versions = listOf("1.2.0"),
            tested = false,
            recommendedModules = listOf("core-compat"),
            blacklist = emptyList()
        )
        val target = Pv7AppTarget(
            packageId = "com.turner.bestparkintheuniverse",
            versionName = "1.2.0",
            abis = setOf("armeabi-v7a"),
            engine = Pv7EngineFingerprint("unity", "4.6.1f1", "libunity.so")
        )
        val resolved = Pv7CatalogResolver.resolve(target, listOf(core, unity, cnrOnly), listOf(untestedGame))
        assertNull(resolved.profile)
        assertTrue("core-compat" in resolved.selectedModuleIds)
        assertTrue("unity-4.6-compat" in resolved.selectedModuleIds)
        assertFalse("cnr-compat" in resolved.selectedModuleIds)
        assertEquals(Pv7ModuleBadge.ENGINE_SUGGESTED,
            resolved.visibleModules.first { it.manifest.id == "unity-4.6-compat" }.badge)
        assertTrue(resolved.issues.isEmpty())
    }
}
