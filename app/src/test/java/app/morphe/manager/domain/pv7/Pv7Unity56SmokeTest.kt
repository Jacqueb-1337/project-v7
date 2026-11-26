package app.morphe.manager.domain.pv7

import android.content.res.AssetManager
import org.junit.Assume.assumeTrue
import org.junit.Test
import sun.misc.Unsafe
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Pv7Unity56SmokeTest {
    @Test
    fun assembleLocalUnity56Guest() {
        val input = System.getenv("PV7_UNITY56_APK")?.let(::File)
        assumeTrue("Set PV7_UNITY56_APK for the optional real-APK smoke test", input?.isFile == true)
        requireNotNull(input)
        val target = Pv7AppTarget("com.sakis25games.gunsandspurs", "1.3.3",
            Pv7EngineDetector.detectAbis(input), Pv7EngineDetector.detect(input))
        assertEquals("5.6.1f1", target.engine?.version)
        assertTrue(Pv7GuestBackend.isExperimentalUnity56(target))
        assertTrue(Pv7GuestBackend.canBuild(target))
        val root = Files.createTempDirectory("pv7-unity56-smoke").toFile()
        try {
            val assembler = Pv7WrapperAssembler()
            val payload = assembler.extractLegacyUnityGuest(input, File(root, "staging"))
            assertTrue(File(payload.dataDir, "globalgamemanagers").isFile)
            assertTrue(File(payload.dataDir, "Managed/mscorlib.dll").isFile)
            val template = sequenceOf(
                File("src/main/assets/pv7/templates/pv7-host-template.apk"),
                File("app/src/main/assets/pv7/templates/pv7-host-template.apk")
            ).first(File::isFile)
            val field = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
            val assets = (field.get(null) as Unsafe).allocateInstance(AssetManager::class.java) as AssetManager
            val output = System.getenv("PV7_UNITY56_OUTPUT")?.let(::File) ?: File(root, "output.apk")
            val catalog = sequenceOf(File("catalog"), File("../catalog")).first { it.isDirectory }
            val dir = File(catalog, "modules/games/com.sakis25games.gunsandspurs/gunsandspurs-compat")
            val compat = Pv7ModuleManifest(id = "gunsandspurs-compat", name = "Guns", version = "0.1.0", kind = Pv7ModuleKind.GAME, description = "Stage25", payloadFiles = listOf("lib/arm64-v8a/libpv7host.so.pv7z"), sourceAssetPath = "file:" + dir.absolutePath)
            assembler.assembleFromTemplate(template, payload, output,
                Pv7WrapperConfig("com.sakis25games.gunsandspurs.pv7", "Guns n Spurs PV7",
                    "1.3.3", 18, guestPackageName = target.packageId),
                assets, listOf(compat))
            ZipFile(output).use { zip ->
                for (name in listOf("assets/guest/libmain.so", "assets/guest/libunity.so",
                    "assets/guest/libmono.so", "assets/bin/Data/globalgamemanagers",
                    "assets/bin/Data/Managed/mscorlib.dll", "lib/arm64-v8a/libpv7host.so")) {
                    assertTrue(zip.getEntry(name) != null, name)
                }
                assertTrue(zip.getEntry("assets/bin/Data/mainData") == null)
                val expected = java.util.zip.GZIPInputStream(File(dir, "payload/lib/arm64-v8a/libpv7host.so.pv7z").inputStream()).use { it.readBytes() }
                assertTrue(zip.getInputStream(zip.getEntry("lib/arm64-v8a/libpv7host.so")).use { it.readBytes() }.contentEquals(expected))
            }
            println("Assembled Unity 5.6 smoke APK: " + output.absolutePath)
        } finally {
            root.deleteRecursively()
        }
    }
}
