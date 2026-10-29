package app.morphe.manager.domain.pv7

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Pv7CompatPropertiesTest {
    @Test fun generalSettingsRemainWhenCnrOverridesOtherProperties() {
        val text = mergePv7CompatProperties(listOf(
            "module.id=unity-4.6\npath.rewritePrivate=true\nmodule.version=1.1.0\n",
            "module.id=cnr\npatch.0.address=0x013d29b8\nmodule.version=1.0.0\n"
        ))
        assertTrue(text.contains("path.rewritePrivate=true"))
        assertTrue(text.contains("module.id=cnr"))
        assertTrue(text.contains("patch.0.address=0x013d29b8"))
        assertFalse(text.contains("module.id=unity-4.6"))
        assertEquals(1, text.lineSequence().count { it.startsWith("module.id=") })
    }
    @Test fun generalModuleNeverInheritsCnrPatches() {
        val text = mergePv7CompatProperties(listOf(
            "# legacy Unity\nmodule.id=unity-4.6\npath.rewritePrivate=true\n"
        ))
        assertTrue(text.contains("module.id=unity-4.6"))
        assertFalse(text.contains("patch.0"))
        assertFalse(text.contains("CNRMods"))
    }
}
