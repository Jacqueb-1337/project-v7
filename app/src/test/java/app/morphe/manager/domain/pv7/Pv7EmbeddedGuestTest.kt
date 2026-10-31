package app.morphe.manager.domain.pv7

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class Pv7EmbeddedGuestTest {
    private fun archive(file: File, entries: Map<String, ByteArray>) {
        ZipOutputStream(FileOutputStream(file)).use { zip ->
            entries.forEach { (path, bytes) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    @Test fun extractsOnlyVerifiedOriginalGuest() {
        val dir = kotlin.io.path.createTempDirectory("pv7-original-").toFile()
        try {
            val game = File(dir, "game.apk")
            archive(game, mapOf("AndroidManifest.xml" to "genuine guest manifest".toByteArray()))
            val sha = Pv7EmbeddedGuest.sha256(game)
            val lock = Pv7ModuleLock(
                guestPackageName = "test.game",
                embeddedGuestPath = Pv7EmbeddedGuest.GUEST_PATH,
                embeddedGuestSha256 = sha
            )
            val wrapper = File(dir, "wrapper.apk")
            archive(wrapper, mapOf(
                Pv7EmbeddedGuest.LOCK_PATH to Pv7EmbeddedGuest.encode(lock).toByteArray(),
                Pv7EmbeddedGuest.GUEST_PATH to game.readBytes()
            ))
            val recovered = File(dir, "recovered.apk")
            assertEquals("test.game", Pv7EmbeddedGuest.extractOriginal(wrapper, recovered).guestPackageName)
            assertArrayEquals(game.readBytes(), recovered.readBytes())

            val tampered = File(dir, "tampered.apk")
            archive(tampered, mapOf(
                Pv7EmbeddedGuest.LOCK_PATH to
                    Pv7EmbeddedGuest.encode(lock.copy(embeddedGuestSha256 = "0".repeat(64))).toByteArray(),
                Pv7EmbeddedGuest.GUEST_PATH to game.readBytes()
            ))
            assertTrue(runCatching {
                Pv7EmbeddedGuest.extractOriginal(tampered, File(dir, "bad.apk"))
            }.isFailure)
        } finally {
            dir.deleteRecursively()
        }
    }
}
