package app.morphe.manager.domain.pv7

import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Pv7EngineDetectorTest {
    @Test fun historicalCocosVersion() {
        val data = "prefix\u0000cocos2d-2.1rc0-x-2.1.2\u0000suffix".toByteArray()
        val found = Pv7EngineDetector.scanCocosNative(ByteArrayInputStream(data), data.size.toLong())
        assertEquals("cocos2d-2.1rc0-x-2.1.2", found.version)
    }

    @Test fun modernCocosVersion() {
        val data = "cocos2d-x 2.2.6\u0000".toByteArray()
        assertEquals("cocos2d-x 2.2.6",
            Pv7EngineDetector.scanCocosNative(ByteArrayInputStream(data), data.size.toLong()).version)
    }

    @Test fun symbolOnlyNativeBinaryDoesNotInventVersion() {
        val data = "_ZN7cocos2d14cocos2dVersionEv\u0000".toByteArray()
        val scan = Pv7EngineDetector.scanCocosNative(ByteArrayInputStream(data), data.size.toLong())
        assertNull(scan.version)
        assertTrue(scan.nativeSignature)
    }

    @Test fun readsEmbeddedCocosVersionFromApk() {
        val apk = File.createTempFile("pv7-cocos-fixture-", ".apk")
        try {
            ZipOutputStream(FileOutputStream(apk)).use { zip ->
                zip.putNextEntry(ZipEntry("lib/armeabi-v7a/libgame.so"))
                zip.write("other data\u0000cocos2d-2.1rc0-x-2.1.2\u0000".toByteArray())
                zip.closeEntry()
            }
            val found = Pv7EngineDetector.detect(apk)
            assertEquals("cocos2dx", found?.id)
            assertEquals("cocos2d-2.1rc0-x-2.1.2", found?.version)
            assertEquals("lib/armeabi-v7a/libgame.so", found?.evidence)
        } finally {
            apk.delete()
        }
    }

    @Test fun locallyAvailableOriginalBestParkApk() {
        val original = File("C:/Users/Jacqueb/Downloads/08093-Best-Park-v1-2-1.apk")
        if (!original.isFile) return
        val result = Pv7EngineDetector.detect(original)
        assertEquals("cocos2dx", result?.id)
        assertEquals("cocos2d-2.1rc0-x-2.1.2", result?.version)
    }
}
