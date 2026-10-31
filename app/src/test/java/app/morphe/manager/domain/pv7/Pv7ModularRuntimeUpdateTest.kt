package app.morphe.manager.domain.pv7

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Pv7ModularRuntimeUpdateTest {
    private fun module(id: String, version: String, family: String, kind: Pv7ModuleKind) =
        Pv7ModuleManifest(
            id = id,
            name = id,
            version = version,
            kind = kind,
            description = "Experimental",
            targets = Pv7TargetSelector(
                abis = listOf("armeabi-v7a"),
                engines = listOf("unity"),
                engineVersions = listOf(family)
            ),
            selectionGroup = "unity-runtime",
            suggestedWhenMatched = true
        )

    private val target = Pv7AppTarget(
        "test.game", "1.0", setOf("armeabi-v7a"),
        Pv7EngineFingerprint("unity", "4.6.1f1", "libunity.so")
    )

    @Test fun picksOnlyBestMatchingFamilyButShowsAlternatives() {
        val old = module("unity-4-6-old", "1.0.0", "4.6.*", Pv7ModuleKind.RUNTIME)
        val current = module("unity-4-6", "1.3.2", "4.6.*", Pv7ModuleKind.RUNTIME)
        val other = module("unity-5", "2.0.0", "5.*", Pv7ModuleKind.RUNTIME)
        val resolved = Pv7CatalogResolver.resolve(target, listOf(old, current, other), emptyList())
        assertEquals(setOf("unity-4-6"), resolved.selectedModuleIds)
        assertEquals(3, resolved.visibleModules.size)
        assertTrue(resolved.visibleModules.any { it.manifest.id == "unity-5" &&
            it.badge == Pv7ModuleBadge.UNTESTED })
        assertTrue(resolved.issues.isEmpty())
    }

    @Test fun manualOverrideAndMultipleSelectionArePreserved() {
        val a = module("unity-4-6", "1.3.2", "4.6.*", Pv7ModuleKind.RUNTIME)
        val b = module("unity-5", "2.0.0", "5.*", Pv7ModuleKind.BACKEND)
        val selection = setOf(a.id, b.id)
        val resolved = Pv7CatalogResolver.resolve(target, listOf(a, b), emptyList(), selection)
        assertEquals(selection, resolved.selectedModuleIds)
        assertEquals(2, resolved.visibleModules.size)
    }

    @Test fun versionedModuleUpdatesDontDowngrade() {
        val lock = Pv7ModuleLock(
            guestPackageName = "test.game",
            modules = listOf(Pv7LockedModule("runtime", "1.8.0", Pv7ModuleKind.RUNTIME))
        )
        val previous = module("runtime", "1.7.9", "4.6.*", Pv7ModuleKind.RUNTIME)
        val newer = previous.copy(version = "1.9.0")
        assertTrue(Pv7ModuleUpgradeAdvisor.available(lock, listOf(previous)).isEmpty())
        val available = Pv7ModuleUpgradeAdvisor.available(lock, listOf(newer))
        assertEquals(1, available.size)
        assertEquals("1.9.0", available.single().availableVersion)
        assertTrue(Pv7ModuleVersions.compare("2.0.0", "1.99.99") > 0)
        assertTrue(Pv7ModuleVersions.compare("1.0.0", "1.0.0-beta.2") > 0)
    }
}
