package app.morphe.manager.domain.pv7

import android.content.res.AssetManager
import kotlinx.serialization.json.Json
import sun.misc.Unsafe
import java.io.File
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertTrue

class LocalCnrAssemblerValidationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun generateRealCnrWrapper() {
        val input = File("local-test/cnr-original.apk").absoluteFile
        val template = File("app/src/main/assets/pv7/templates/pv7-host-template.apk").absoluteFile
        val staging = File("local-test/assembler-work").absoluteFile
        val output = File("local-test/cnr-assembled.apk").absoluteFile

        require(input.isFile) { "Missing original CNR APK: $input" }
        require(template.isFile) { "Missing host template: $template" }
        staging.deleteRecursively()
        output.delete()

        val assembler = Pv7WrapperAssembler()
        val guest = assembler.extractLegacyUnityGuest(input, staging)
        val modules = listOf(
            loadModule("catalog/modules/general/core-compat/module.json"),
            loadModule("catalog/modules/games/com.joydo.minestrikenew/cnr-compat/module.json")
        )

        assembler.assembleFromTemplate(
            templateApk = template,
            guestPayload = guest,
            outputApk = output,
            config = Pv7WrapperConfig(
                packageName = "com.joydo.minestrikenew.pv7",
                launcherLabel = "CopNRobber",
                versionName = "3.0.2-cnr1",
                versionCode = 4,
                guestPackageName = "com.joydo.minestrikenew",
                requestedPermissions = setOf(
                    "android.permission.INTERNET",
                    "android.permission.ACCESS_NETWORK_STATE",
                    "android.permission.RECORD_AUDIO",
                    "android.permission.READ_EXTERNAL_STORAGE",
                    "android.permission.WRITE_EXTERNAL_STORAGE",
                    "com.android.vending.BILLING"
                )
            ),
            assets = uninitializedAssetManager(),
            selectedModules = modules
        )

        assertTrue(output.isFile)
        ZipFile(output).use { zip ->
            assertTrue(zip.getEntry("assets/bootstrap/CNRModManager.dll") != null)
            assertTrue(zip.getEntry("assets/bootstrap/CNRModManager.dll.sha256") != null)
            val host = zip.getInputStream(zip.getEntry("assets/pv7/host.properties"))
                .bufferedReader().use { it.readText() }
            assertTrue("bootstrap.0.toFilesRelative=CNRMods/CNRModManager.dll" in host)
        }
        println("PV7_CNR_ASSEMBLED=" + output.absolutePath)
    }

    private fun loadModule(relative: String): Pv7ModuleManifest {
        val file = File(relative).absoluteFile
        require(file.isFile) { "Missing module: $file" }
        return json.decodeFromString<Pv7ModuleManifest>(file.readText())
            .copy(sourceAssetPath = "file:" + file.parentFile.absolutePath)
    }

    private fun uninitializedAssetManager(): AssetManager {
        val field = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = field.get(null) as Unsafe
        return unsafe.allocateInstance(AssetManager::class.java) as AssetManager
    }
}
