package app.morphe.manager.domain.pv7

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class Pv7CocosGuestPackagerTest {
    private fun fakeArm32Elf(): ByteArray = ByteArray(100).apply {
        this[0] = 0x7f
        this[1] = 'E'.code.toByte()
        this[2] = 'L'.code.toByte()
        this[3] = 'F'.code.toByte()
        this[4] = 1  // ELFCLASS32
        this[5] = 1  // ELFDATA2LSB
        this[18] = 40 // EM_ARM
        this[19] = 0
    }

    private fun fixture(root: File, library: ByteArray, abi: String = "armeabi"): File {
        val apk = File(root, "best-park.apk")
        ZipOutputStream(apk.outputStream()).use { zip ->
            val entries = linkedMapOf(
                "lib/$abi/libgame.so" to library,
                "org/cocos2dx/lib/Cocos2dxRenderer.class" to byteArrayOf(1),
                "assets/level1.dat" to byteArrayOf(17, 18, 19)
            )
            entries.forEach { (name, data) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
        return apk
    }

    @Test fun stagesNativeLibraryAndOriginalAssetsWithoutInventingUnityOrObb() {
        val root = Files.createTempDirectory("pv7-cocos-stage").toFile()
        try {
            val image = fakeArm32Elf()
            val apk = fixture(root, image)
            val result = Pv7CocosGuestPackager.stage(apk, File(root, "stage"))
            assertEquals("armeabi", result.abi)
            assertArrayEquals(image, result.nativeLibrary.readBytes())
            assertArrayEquals(apk.readBytes(), result.originalApk.readBytes())
            assertTrue(result.bundledLibraries.isEmpty())
            assertFalse(File(result.root, "assets/guest/libunity.so").exists())
            assertFalse(File(result.root, "assets/guest/libmono.so").exists())
            assertFalse(File(result.root, "assets/guest/obb").exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun rejectsOtherArchitecturesBeforeModifyingExistingStaging() {
        val root = Files.createTempDirectory("pv7-cocos-invalid").toFile()
        try {
            val invalid = fakeArm32Elf().apply { this[18] = 0x3e }
            val apk = fixture(root, invalid)
            val stage = File(root, "stage").apply { mkdirs() }
            val marker = File(stage, "important.txt").apply { writeText("preserved") }
            assertThrows(IOException::class.java) {
                Pv7CocosGuestPackager.stage(apk, stage)
            }
            assertEquals("preserved", marker.readText())
        } finally { root.deleteRecursively() }
    }

    @Test fun supportsArmV7WhenOldArmeabiIsAbsent() {
        val root = Files.createTempDirectory("pv7-cocos-v7").toFile()
        try {
            val result = Pv7CocosGuestPackager.stage(
                fixture(root, fakeArm32Elf(), "armeabi-v7a"),
                File(root, "stage")
            )
            assertEquals("armeabi-v7a", result.abi)
        } finally { root.deleteRecursively() }
    }

    @Test fun optionalOriginalBestParkApkStagesWithoutShippingCopyrightedGame() {
        // Explicit opt-in local smoke test. No game files enter GitHub CI or this repository.
        val path = System.getenv("PV7_COCOS_APK") ?: return
        val root = Files.createTempDirectory("pv7-bestpark-original").toFile()
        try {
            val result = Pv7CocosGuestPackager.stage(File(path), File(root, "stage"))
            assertTrue(result.nativeLibrary.length() > 1024)
            assertEquals("armeabi", result.abi)
            assertTrue(result.originalApk.length() > 1024)
        } finally { root.deleteRecursively() }
    }
}

