package app.morphe.manager.domain.pv7

import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

data class Pv7EngineFingerprint(
    val id: String,
    val version: String?,
    val evidence: String
)

object Pv7EngineDetector {
    private val unityVersionPattern = Regex(
        """(?<![0-9])([0-9]{1,4}\.[0-9]{1,3}\.[0-9]{1,3}[abcfp][0-9]+)(?![0-9])"""
    )

    fun detectAbis(apk: File): Set<String> =
        runCatching {
            ZipFile(apk).use { zip ->
                zip.entries().asSequence()
                    .filterNot(ZipEntry::isDirectory)
                    .mapNotNull { entry ->
                        val parts = entry.name.split('/')
                        parts.getOrNull(1).takeIf {
                            parts.firstOrNull() == "lib" &&
                                it in setOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
                        }
                    }
                    .toSet()
            }
        }.getOrDefault(emptySet())

    fun detect(apk: File): Pv7EngineFingerprint? {
        ZipFile(apk).use { zip ->
            val entries = zip.entries().asSequence().toList()

            val globalManagers = entries
                .filterNot(ZipEntry::isDirectory)
                .filter { it.name.endsWith("/globalgamemanagers") || it.name == "globalgamemanagers" }

            for (entry in globalManagers) {
                val version = zip.getInputStream(entry).use {
                    scanUnityVersion(it, maxBytes = 8L * 1024L * 1024L)
                }
                if (version != null) {
                    return Pv7EngineFingerprint(
                        id = "unity",
                        version = version,
                        evidence = entry.name
                    )
                }
            }

            val unityLibraries = entries
                .filterNot(ZipEntry::isDirectory)
                .filter { it.name.endsWith("/libunity.so") }

            for (entry in unityLibraries) {
                val version = zip.getInputStream(entry).use {
                    scanUnityVersion(it, maxBytes = 64L * 1024L * 1024L)
                }
                if (version != null) {
                    return Pv7EngineFingerprint(
                        id = "unity",
                        version = version,
                        evidence = entry.name
                    )
                }
            }

            // libunity.so is itself enough to identify the engine even when a
            // stripped/custom build no longer exposes an exact version string.
            unityLibraries.firstOrNull()?.let { entry ->
                return Pv7EngineFingerprint(
                    id = "unity",
                    version = null,
                    evidence = entry.name
                )
            }
        }

        return null
    }

    private fun scanUnityVersion(input: InputStream, maxBytes: Long): String? {
        val buffer = ByteArray(64 * 1024)
        var carry = ""
        var total = 0L

        while (total < maxBytes) {
            val remaining = (maxBytes - total).coerceAtMost(buffer.size.toLong()).toInt()
            val read = input.read(buffer, 0, remaining)
            if (read < 0) break
            total += read

            // Unity's version token is ASCII. ISO-8859-1 keeps byte positions
            // one-to-one and avoids decode failures on binary data.
            val chunk = carry + String(buffer, 0, read, Charsets.ISO_8859_1)
            unityVersionPattern.find(chunk)?.groupValues?.getOrNull(1)?.let { return it }

            carry = chunk.takeLast(96)
        }

        return null
    }
}
