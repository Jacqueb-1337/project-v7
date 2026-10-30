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

    // Historical Cocos2d-x version strings vary across release candidates:
    // "cocos2d-2.1rc0-x-2.1.2" and "cocos2d-x 2.2.6" both occur in official
    // sources. Capture whole tokens, not just a guessed semantic version.
    private val cocosVersionPattern = Regex(
        """(?<![A-Za-z0-9_])(cocos2d-(?:[0-9][A-Za-z0-9.]*-x-[0-9]+(?:\.[0-9]+){1,3}|x[ -][0-9]+(?:\.[0-9]+){1,3}))(?![A-Za-z0-9_.-])"""
    )
    private val cocosNativeSymbols = listOf(
        "Java_org_cocos2dx_lib_Cocos2dxRenderer_nativeInit",
        "_ZN7cocos2d14cocos2dVersionEv"
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
                                it in setOf("armeabi", "armeabi-v7a", "arm64-v8a", "x86", "x86_64")
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

            // Scan game-owned native ELF images for the engine's compiled-in
            // version. This is read-only and does not execute untrusted APK code.
            // Prefer a direct version to symbol-only evidence.
            var cocosFallback: String? = null
            for (entry in entries) {
                if (entry.isDirectory ||
                    !entry.name.startsWith("lib/") ||
                    !entry.name.endsWith(".so") ||
                    entry.name.endsWith("/libunity.so")) continue

                val scan = zip.getInputStream(entry).use {
                    scanCocosNative(it, maxBytes = 128L * 1024L * 1024L)
                }
                if (scan.version != null) {
                    return Pv7EngineFingerprint(
                        id = "cocos2dx",
                        version = scan.version,
                        evidence = entry.name
                    )
                }
                if (scan.nativeSignature) cocosFallback = entry.name
            }

            if (cocosFallback != null) {
                return Pv7EngineFingerprint(
                    id = "cocos2dx",
                    version = null,
                    evidence = cocosFallback
                )
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

            // Many older Cocos2d-x games bundle one ARM32 libgame.so (not
            // Unity's libmain.so/libmono.so) and org/cocos2dx/lib resources.
            // Never send them through the Unity wrapper backend.
            val cocosEvidence = entries.firstOrNull { entry ->
                !entry.isDirectory &&
                    entry.name.startsWith("org/cocos2dx/lib/")
            }
            val cocosNative = entries.firstOrNull { entry ->
                !entry.isDirectory &&
                    Regex("^lib/(armeabi|armeabi-v7a)/lib(game|cocos2dcpp)\\.so$")
                        .matches(entry.name)
            }
            if (cocosEvidence != null && cocosNative != null) {
                return Pv7EngineFingerprint(
                    id = "cocos2dx",
                    version = null,
                    evidence = cocosNative.name + " + " + cocosEvidence.name
                )
            }
        }

        return null
    }

    internal data class CocosNativeScan(val version: String?, val nativeSignature: Boolean)

    internal fun scanCocosNative(input: InputStream, maxBytes: Long): CocosNativeScan {
        val buffer = ByteArray(64 * 1024)
        var carry = ""
        var total = 0L
        var symbolFound = false
        while (total < maxBytes) {
            val remaining = (maxBytes - total).coerceAtMost(buffer.size.toLong()).toInt()
            val read = input.read(buffer, 0, remaining)
            if (read < 0) break
            if (read == 0) continue
            total += read
            val chunk = carry + String(buffer, 0, read, Charsets.ISO_8859_1)
            cocosVersionPattern.find(chunk)?.groupValues?.getOrNull(1)?.let {
                return CocosNativeScan(it, true)
            }
            if (cocosNativeSymbols.any { chunk.contains(it) }) symbolFound = true
            carry = chunk.takeLast(128)
        }
        return CocosNativeScan(version = null, nativeSignature = symbolFound)
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
