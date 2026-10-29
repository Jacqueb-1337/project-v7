package app.morphe.manager.domain.pv7

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class Pv7EngineDetectorTest {
    private fun fakeApk(vararg entries: Pair<String, String>): File {
        val temp = File.createTempFile("pv7-engine-", ".apk")
        ZipOutputStream(temp.outputStream()).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        temp.deleteOnExit()
        return temp
    }

    @Test fun detectsCocos2dxAndOldArmeabiWithoutMistakingItForUnity() {
        val apk = fakeApk(
            "lib/armeabi/libgame.so" to "ELF native cocos2dx",
            "org/cocos2dx/lib/Cocos2dxHelper.gp" to "game",
            "assets/missions.ini" to "values"
        )
        val engine = Pv7EngineDetector.detect(apk)
        assertEquals("cocos2dx", engine?.id)
        assertTrue("armeabi" in Pv7EngineDetector.detectAbis(apk))
        val target = Pv7AppTarget("com.turner.bestparkintheuniverse", "1.2.1",
            Pv7EngineDetector.detectAbis(apk), engine)
        assertFalse(Pv7GuestBackend.canBuild(target))
        assertTrue(Pv7GuestBackend.explanation(target).contains("Cocos2d-x"))
    }

    @Test fun genericLibgameWithoutCocosEvidenceIsNotMisclassified() {
        val apk = fakeApk("lib/armeabi/libgame.so" to "ELF")
        assertEquals(null, Pv7EngineDetector.detect(apk))
    }

    @Test fun preservesTheTestedUnity46Path() {
        val apk = fakeApk(
            "lib/armeabi-v7a/libunity.so" to "Unity 4.6.1f1",
            "lib/armeabi-v7a/libmain.so" to "libmain",
            "lib/armeabi-v7a/libmono.so" to "libmono",
            "assets/bin/Data/mainData" to "data"
        )
        val target = Pv7AppTarget("com.joydo.minestrikenew", "3.0.2",
            Pv7EngineDetector.detectAbis(apk), Pv7EngineDetector.detect(apk))
        assertTrue(Pv7GuestBackend.canBuild(target))
    }

    @Test fun newerUnityAndUnrecognizedEnginesCannotUseTheUnity46Wrapper() {
        val newer = Pv7AppTarget("any", "1", setOf("armeabi-v7a"),
            Pv7EngineFingerprint("unity", "2019.4.2f1", "libunity.so"))
        assertFalse(Pv7GuestBackend.canBuild(newer))
        assertFalse(Pv7GuestBackend.canBuild(Pv7AppTarget("any", "1", setOf("armeabi"), null)))
    }
}
