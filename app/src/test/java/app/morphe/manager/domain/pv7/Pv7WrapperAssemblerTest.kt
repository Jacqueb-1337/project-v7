package app.morphe.manager.domain.pv7

import android.content.res.AssetManager
import com.reandroid.apk.ApkModule
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import sun.misc.Unsafe
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class Pv7WrapperAssemblerTest {
    @Test
    fun wrapperPreservesPermissionsAndProvider() {
        val root = Files.createTempDirectory("pv7-wrapper-test").toFile()
        try {
            val template = sequenceOf(
                File("src/main/assets/pv7/templates/pv7-host-template.apk"),
                File("app/src/main/assets/pv7/templates/pv7-host-template.apk")
            ).firstOrNull(File::isFile)
            assertNotNull(template)

            val guestRoot = File(root, "guest")
            val data = File(guestRoot, "assets/bin/Data")
            val managed = File(data, "Managed").apply { mkdirs() }
            File(data, "mainData").apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(1)) }
            File(managed, "mscorlib.dll").writeBytes(byteArrayOf(1))
            val libMain = File(guestRoot, "assets/guest/libmain.so").apply {
                parentFile?.mkdirs()
                writeBytes(byteArrayOf(1))
            }
            val libUnity = File(guestRoot, "assets/guest/libunity.so").apply {
                writeBytes(byteArrayOf(1))
            }
            val libMono = File(guestRoot, "assets/guest/libmono.so").apply {
                writeBytes(byteArrayOf(1))
            }

            val module = Pv7ModuleManifest(
                id = "test-provider",
                name = "Test provider",
                version = "1",
                kind = Pv7ModuleKind.GAME,
                description = "test",
                operations = listOf(
                    Pv7PatchOperation(
                        type = "wrapper.addDocumentsProvider",
                        value = buildJsonObject {
                            put("className", "app.projectv7.wrapper.CNRModsDocumentsProvider")
                            put("authoritySuffix", ".documents")
                        }
                    )
                )
            )

            val output = File(root, "output.apk")
            Pv7WrapperAssembler().assembleFromTemplate(
                templateApk = template,
                guestPayload = Pv7GuestPayload(guestRoot, libMain, libUnity, libMono, data),
                outputApk = output,
                config = Pv7WrapperConfig(
                    packageName = "com.example.pv7",
                    launcherLabel = "PV7 Test",
                    requestedPermissions = setOf(
                        "android.permission.INTERNET",
                        "android.permission.WRITE_EXTERNAL_STORAGE",
                        "android.permission.READ_EXTERNAL_STORAGE",
                        "android.permission.RECORD_AUDIO"
                    )
                ),
                assets = uninitializedAssetManager(),
                selectedModules = listOf(module)
            )

            assertTrue(output.isFile)
            val apk = ApkModule.loadApkFile(output)
            try {
                val manifest = apk.androidManifestBlock
                assertTrue("android.permission.WRITE_EXTERNAL_STORAGE" in manifest.usesPermissions)
                assertTrue("android.permission.READ_EXTERNAL_STORAGE" in manifest.usesPermissions)
                assertTrue("android.permission.RECORD_AUDIO" in manifest.usesPermissions)

                val provider = manifest.listApplicationElementsByTag("provider")
                    .singleOrNull {
                        com.reandroid.arsc.chunk.xml.AndroidManifestBlock.getAndroidNameValue(it) ==
                            "app.projectv7.wrapper.CNRModsDocumentsProvider"
                    }
                assertNotNull(provider)
                assertEquals(
                    "com.example.pv7.documents",
                    provider.searchAttributeByName("authorities")?.valueAsString
                )
            } finally {
                apk.close()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun uninitializedAssetManager(): AssetManager {
        val field = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = field.get(null) as Unsafe
        return unsafe.allocateInstance(AssetManager::class.java) as AssetManager
    }
}
