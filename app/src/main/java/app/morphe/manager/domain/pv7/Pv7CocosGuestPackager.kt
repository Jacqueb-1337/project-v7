package app.morphe.manager.domain.pv7

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** Stages ARM32 Cocos2d-x guests separately from the Unity/Mono wrapper. */
data class Pv7CocosGuestPayload(
    val root: File,
    val nativeLibrary: File,
    val originalApk: File,
    val abi: String,
    val bundledLibraries: List<File>
)

/**
 * Prepares native Cocos game libraries and the original asset archive.
 * The engine backend is still under development; this does not enable
 * patching until the corresponding JNI/ARM32 runtime exists.
 */
object Pv7CocosGuestPackager {
    private val supportedAbis = listOf("armeabi-v7a", "armeabi")
    private const val MAX_NATIVE_BYTES = 192L * 1024L * 1024L

    fun stage(inputApk: File, stagingRoot: File): Pv7CocosGuestPayload {
        if (!inputApk.isFile) throw IOException("Input APK is missing: $inputApk")
        if (Pv7EngineDetector.detect(inputApk)?.id != "cocos2dx") {
            throw IOException("Input is not a recognized Cocos2d-x APK")
        }
        ZipFile(inputApk).use { zip ->
            val abi = supportedAbis.firstOrNull { candidate ->
                zip.getEntry("lib/$candidate/libgame.so") != null ||
                    zip.getEntry("lib/$candidate/libcocos2dcpp.so") != null
            } ?: throw IOException("No 32-bit ARM Cocos2d-x guest library was found")
            val mainEntry = sequenceOf(
                "lib/$abi/libgame.so", "lib/$abi/libcocos2dcpp.so"
            ).mapNotNull(zip::getEntry).firstOrNull()
                ?: throw IOException("Required Cocos2d-x library is missing")
            validateArm32(zip, mainEntry)
            val staged = File(stagingRoot, "assets/guest")
            if (stagingRoot.exists() && !stagingRoot.deleteRecursively()) {
                throw IOException("Could not reset guest staging directory")
            }
            if (!staged.mkdirs() && !staged.isDirectory) {
                throw IOException("Could not create guest staging directory")
            }
            val native = File(staged, "libgame.so")
            val original = File(staged, "original.apk")
            try {
                copyEntry(zip, mainEntry, native)
                // Preserving original.apk allows a future backend to read the
                // game's assets/resources without colliding with host assets.
                inputApk.copyTo(original, overwrite = true)
                val extra = mutableListOf<File>()
                val nativePrefix = "lib/$abi/"
                zip.entries().asSequence()
                    .filterNot(ZipEntry::isDirectory)
                    .filter { it.name.startsWith(nativePrefix) && it.name.endsWith(".so") }
                    .filterNot { it.name == mainEntry.name }
                    .forEach { entry ->
                        validateArm32(zip, entry)
                        val filename = entry.name.removePrefix(nativePrefix)
                        if (filename.contains('/') || filename == "." || filename == "..") {
                            throw IOException("Unsafe native library filename")
                        }
                        val output = File(staged, "libs/$filename")
                        copyEntry(zip, entry, output)
                        extra += output
                    }
                // No embedded OBB: game expansion files are user-managed via
                // the wrapper's private GuestSharedStorage/obb SAF folder.
                return Pv7CocosGuestPayload(stagingRoot, native, original, abi, extra)
            } catch (failure: Exception) {
                stagingRoot.deleteRecursively()
                throw failure
            }
        }
    }

    private fun validateArm32(zip: ZipFile, entry: ZipEntry) {
        if (entry.size > MAX_NATIVE_BYTES) throw IOException("Native library exceeds size limit")
        val head = ByteArray(20)
        val read = zip.getInputStream(entry).use { input ->
            var offset = 0
            while (offset < head.size) {
                val n = input.read(head, offset, head.size - offset)
                if (n < 0) break
                offset += n
            }
            offset
        }
        val valid = read == head.size &&
            head[0] == 0x7f.toByte() &&
            head[1] == 'E'.code.toByte() &&
            head[2] == 'L'.code.toByte() &&
            head[3] == 'F'.code.toByte() &&
            head[4] == 1.toByte() &&
            head[5] == 1.toByte() &&
            head[18] == 40.toByte() &&
            head[19] == 0.toByte()
        if (!valid) throw IOException("Not an ARM32 little-endian ELF guest: " + entry.name)
    }

    private fun copyEntry(zip: ZipFile, entry: ZipEntry, target: File) {
        target.parentFile?.mkdirs()
        try {
            var copied = 0L
            zip.getInputStream(entry).use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        copied += n
                        if (copied > MAX_NATIVE_BYTES) {
                            throw IOException("Native library exceeds size limit")
                        }
                        output.write(buffer, 0, n)
                    }
                }
            }
        } catch (failure: Exception) {
            target.delete()
            throw failure
        }
    }
}
