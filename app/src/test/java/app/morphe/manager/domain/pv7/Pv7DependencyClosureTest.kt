package app.morphe.manager.domain.pv7

import org.junit.Assert.*
import org.junit.Test

class Pv7DependencyClosureTest {
    private fun module(id: String, vararg deps: String) = Pv7ModuleManifest(
        id = id, name = id, version = "1", kind = Pv7ModuleKind.GENERAL, description = id,
        dependencies = deps.toList()
    )

    @Test fun downloadsOnlySelectedDependencyClosure() {
        val core = module("core")
        val unity = module("unity", "core")
        val game = module("game", "unity", "core")
        val other = module("other")
        assertEquals(listOf(core, unity, game),
            pv7DependencyClosure(listOf(other, game, unity, core), setOf("game")))
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsMissingDependency() {
        pv7DependencyClosure(listOf(module("game", "missing")), setOf("game"))
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsCycle() {
        pv7DependencyClosure(listOf(module("a", "b"), module("b", "a")), setOf("a"))
    }
}
