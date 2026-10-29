package app.morphe.manager.domain.pv7

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

class Pv7HostStorageProviderTest {
    @Test fun compiledTemplateContainsIndependentlyRegisteredGameFilesProvider() {
        val template = File("src/main/assets/pv7/templates/pv7-host-template.apk")
        assertTrue("PV7 host template is missing", template.isFile)
        ZipFile(template).use { zip ->
            assertTrue("Original host DEX must remain", zip.getEntry("classes.dex") != null)
            val extra = zip.getEntry("classes2.dex")
            assertTrue("Game storage provider DEX must be embedded", extra != null)
            val dex = zip.getInputStream(extra!!).use { it.readBytes() }
            val text = String(dex, Charsets.ISO_8859_1)
            assertTrue(
                "Missing compiled SAF provider; rerun embed_storage_provider.py",
                text.contains("Pv7GameStorageDocumentsProvider")
            )
        }
    }
}
