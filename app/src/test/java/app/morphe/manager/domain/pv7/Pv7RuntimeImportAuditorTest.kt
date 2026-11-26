package app.morphe.manager.domain.pv7

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class Pv7RuntimeImportAuditorTest {
    private fun elf(importName: String?, exportName: String? = null): ByteArray {
        val b = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(0, 0x464c457f)
        b.put(4, 1); b.put(5, 1)
        b.putShort(16, 3); b.putShort(18, 40)
        b.putInt(32, 256); b.putShort(46, 40); b.putShort(48, 4)
        val name = importName ?: exportName ?: "unused"
        val text = name.toByteArray(Charsets.UTF_8)
        text.copyInto(b.array(), 65)
        // section 1: string table
        b.putInt(300, 3); b.putInt(312, 64); b.putInt(316, text.size + 2)
        // section 2: dynamic symbol table, null symbol + one symbol
        b.putInt(340, 11); b.putInt(352, 128); b.putInt(356, 32)
        b.putInt(360, 1); b.putInt(372, 16)
        b.putInt(144, 1); b.put(156, 0x12)
        b.putShort(158, if (exportName != null) 1.toShort() else 0.toShort())
        // section 3: relocation table
        b.putInt(380, 9); b.putInt(392, 192)
        b.putInt(396, if (importName != null) 8 else 0)
        b.putInt(400, 2); b.putInt(412, 8)
        if (importName != null) b.putInt(196, (1 shl 8) or 22)
        return b.array()
    }

    @Test fun resolvesGuestAndReportsMissingImports() {
        val apk = File.createTempFile("pv7-audit", ".apk")
        try {
            ZipOutputStream(apk.outputStream()).use { z ->
                mapOf("libmain.so" to elf("missing"), "libunity.so" to elf("provided"),
                    "libmono.so" to elf(null, "provided")).forEach { (name, bytes) ->
                    z.putNextEntry(ZipEntry("lib/armeabi-v7a/$name"))
                    z.write(bytes); z.closeEntry()
                }
            }
            val audit = Pv7RuntimeImportAuditor.inspectUnity(apk, emptySet())
            assertTrue(audit.complete)
            assertEquals(2, audit.importCount)
            assertEquals(1, audit.guestResolved)
            assertEquals(listOf("missing"), audit.gaps.map { it.symbol })
            val covered = Pv7RuntimeImportAuditor.inspectUnity(apk, setOf("missing"))
            assertEquals(1, covered.handlerMatches)
            assertTrue(covered.gaps.isEmpty())
            val source = Pv7RuntimeImportAuditor.inspectUnity(apk, emptySet(), setOf("missing"))
            assertEquals(0, source.handlerMatches)
            assertTrue(source.gaps.isEmpty())
            assertEquals(listOf("missing"), source.sourceCandidates.map { it.symbol })
        } finally { apk.delete() }
    }

    @Test fun malformedInputIsNotCleanCoverage() {
        val apk = File.createTempFile("pv7-audit", ".apk")
        try {
            apk.writeText("not a zip")
            val audit = Pv7RuntimeImportAuditor.inspectUnity(apk, emptySet())
            assertFalse(audit.complete)
            assertTrue(audit.notes.isNotEmpty())
        } finally { apk.delete() }
    }
    @Test fun selectsLastNativeOverrideInAssemblerOrder() {
        val base = Pv7ModuleManifest(id = "core", name = "Core", version = "1", kind = Pv7ModuleKind.GENERAL, description = "Core")
        val engine = base.copy(id = "unity-5.6-compat", payloadFiles = listOf("lib/arm64-v8a/libpv7host.so.pv7z"))
        val game = base.copy(id = "game", kind = Pv7ModuleKind.GAME)
        val override = game.copy(payloadFiles = listOf("lib/arm64-v8a/libpv7host.so"))
        assertNull(Pv7RuntimeImportAuditor.runtimeOverride(listOf(base, game)))
        assertEquals(engine, Pv7RuntimeImportAuditor.runtimeOverride(listOf(game, engine, base)))
        assertEquals(override, Pv7RuntimeImportAuditor.runtimeOverride(listOf(override, engine)))
    }
}
