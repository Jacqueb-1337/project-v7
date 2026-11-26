package app.morphe.manager.domain.pv7

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

data class Pv7RuntimeGap(val symbol: String, val libraries: List<String>, val weak: Boolean)
data class Pv7RuntimeAudit(
    val abi: String? = null,
    val importCount: Int = 0,
    val guestResolved: Int = 0,
    val handlerMatches: Int = 0,
    val gaps: List<Pv7RuntimeGap> = emptyList(),
    val dataSymbols: List<Pv7RuntimeGap> = emptyList(),
    val notes: List<String> = emptyList(),
    val complete: Boolean = false,
    val sourceCandidates: List<Pv7RuntimeGap> = emptyList()
)

/** Static ELF32 relocation coverage. Does not execute the input or claim runtime support. */
object Pv7RuntimeImportAuditor {
    private const val MAX_LIBRARY = 64 * 1024 * 1024
    private const val MAX_TOTAL = 128L * 1024 * 1024
    private val unityLibraries = setOf("libmain.so", "libunity.so", "libmono.so")
    private data class Symbol(val name: String, val defined: Boolean, val bind: Int, val type: Int, val visibility: Int)
    private data class Section(val type: Long, val offset: Long, val size: Long, val link: Long, val entry: Long)
    private data class Import(val name: String, val library: String, val weak: Boolean, val function: Boolean)
    private data class Image(val exports: Set<String>, val imports: List<Import>)

    fun runtimeOverride(modules: List<Pv7ModuleManifest>): Pv7ModuleManifest? =
        modules.sortedWith(compareBy<Pv7ModuleManifest> {
            if (it.kind == Pv7ModuleKind.GENERAL) 0 else 1
        }.thenBy { it.id }).lastOrNull { module ->
            module.payloadFiles.any { it == "lib/arm64-v8a/libpv7host.so" ||
                it == "lib/arm64-v8a/libpv7host.so.pv7z" }
        }

    fun inspectUnity(apk: File, handlers: Set<String>, sourceOnly: Set<String> = emptySet()): Pv7RuntimeAudit {
        return try {
            ZipFile(apk).use { zip ->
                val entries = zip.entries().asSequence().filterNot { it.isDirectory }.toList()
                val abi = listOf("armeabi-v7a", "armeabi").firstOrNull { a ->
                    entries.any { it.name == "lib/$a/libunity.so" }
                } ?: return Pv7RuntimeAudit(notes = listOf("No ARM32 Unity library found."))
                val candidates = entries.filter { it.name.startsWith("lib/$abi/") && it.name.endsWith(".so") }
                val selected = candidates.filter { it.name.substringAfterLast('/') in unityLibraries }
                val notes = mutableListOf<String>()
                val missing = unityLibraries - selected.map { it.name.substringAfterLast('/') }.toSet()
                if (missing.isNotEmpty()) notes += "Required Unity libraries absent: " + missing.sorted().joinToString()
                val extra = candidates.filterNot { it in selected }.map { it.name.substringAfterLast('/') }
                if (extra.isNotEmpty()) notes += "Additional native libraries not covered by this Unity loader: " + extra.joinToString()
                require(selected.size <= 3 && selected.map { it.name }.distinct().size == selected.size)
                require(selected.all { it.size in 1L..MAX_LIBRARY.toLong() } && selected.sumOf { it.size } <= MAX_TOTAL) {
                    "Native library exceeds inspection size limit."
                }
                val images = selected.map { entry ->
                    zip.getInputStream(entry).use { input ->
                        val bytes = ByteArray(entry.size.toInt())
                        var offset = 0
                        while (offset < bytes.size) {
                            val read = input.read(bytes, offset, bytes.size - offset)
                            require(read > 0) { "Truncated native library." }
                            offset += read
                        }
                        require(input.read() == -1) { "Native library size mismatch." }
                        parse(bytes, entry.name.substringAfterLast('/'))
                    }
                }
                val exports = images.flatMap { it.exports }.toSet()
                val grouped = images.flatMap { it.imports }.groupBy { it.name }
                val gaps = mutableListOf<Pv7RuntimeGap>()
                val data = mutableListOf<Pv7RuntimeGap>()
                val source = mutableListOf<Pv7RuntimeGap>()
                var guest = 0
                var bridge = 0
                for ((name, refs) in grouped.toSortedMap()) {
                    val row = Pv7RuntimeGap(name, refs.map { it.library }.distinct().sorted(), refs.all { it.weak })
                    when {
                        name in exports -> guest++
                        refs.none { it.function } -> data += row
                        name.substringBefore('@') in handlers -> bridge++
                        name.substringBefore('@') in sourceOnly -> source += row
                        else -> gaps += row
                    }
                }
                Pv7RuntimeAudit(abi, grouped.size, guest, bridge, gaps, data, notes, missing.isEmpty() && extra.isEmpty(), source)
            }
        } catch (e: Exception) {
            Pv7RuntimeAudit(notes = listOf("Native import inspection incomplete: " + (e.message ?: "invalid ELF")))
        }
    }

    private fun parse(bytes: ByteArray, library: String): Image {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun range(off: Long, len: Long) {
            require(off >= 0 && len >= 0 && off <= bytes.size.toLong() && len <= bytes.size.toLong() - off) {
                "ELF range outside $library"
            }
        }
        fun u8(off: Long): Int { range(off, 1); return bytes[off.toInt()].toInt() and 255 }
        fun u16(off: Long): Int { range(off, 2); return b.getShort(off.toInt()).toInt() and 65535 }
        fun u32(off: Long): Long { range(off, 4); return b.getInt(off.toInt()).toLong() and 0xffffffffL }
        require(u32(0) == 0x464c457fL && u8(4) == 1 && u8(5) == 1 && u16(18) == 40 && u16(16) == 3) { "Not ARM32 ELF: $library" }
        val shoff = u32(32)
        val shsize = u16(46)
        val shnum = u16(48)
        require(shsize >= 40 && shnum in 1..8192) { "Unsupported or missing ELF section table: $library" }
        range(shoff, shsize.toLong() * shnum)
        val sections = (0 until shnum).map { i ->
            val at = shoff + i.toLong() * shsize
            Section(u32(at+4), u32(at+16), u32(at+20), u32(at+24), u32(at+36))
        }
        val tables = mutableMapOf<Int, List<Symbol>>()
        fun symbols(index: Int): List<Symbol> = tables.getOrPut(index) {
            val sec = sections[index]
            require(sec.type == 11L && sec.entry >= 16 && sec.size % sec.entry == 0L && sec.size / sec.entry <= 200000)
            range(sec.offset, sec.size)
            val strings = sections[sec.link.toInt()]
            require(strings.type == 3L)
            range(strings.offset, strings.size)
            (0 until (sec.size / sec.entry).toInt()).map { n ->
                val at = sec.offset + n.toLong() * sec.entry
                val nameOff = u32(at)
                require(nameOff < strings.size)
                val start = (strings.offset + nameOff).toInt()
                val limit = (strings.offset + strings.size).toInt()
                var end = start
                while (end < limit && bytes[end].toInt() != 0) {
                    require(end - start < 4096)
                    end++
                }
                require(end < limit)
                val info = u8(at+12)
                Symbol(String(bytes, start, end-start, Charsets.UTF_8), u16(at+14) != 0, info ushr 4, info and 15, u8(at+13) and 3)
            }
        }
        val dyn = sections.indices.filter { sections[it].type == 11L }
        require(dyn.isNotEmpty()) { "No dynamic symbols in $library" }
        val exports = dyn.flatMap(::symbols).filter { it.defined && it.name.isNotEmpty() && it.bind in 1..2 && it.visibility in listOf(0,3) }.map { it.name }.toSet()
        val imports = mutableListOf<Import>()
        var relocationCount = 0L
        for (sec in sections.filter { it.type == 9L || it.type == 4L }) {
            val index = sec.link.toInt()
            if (index !in dyn) continue
            require(sec.entry >= (if (sec.type == 9L) 8 else 12) && sec.size % sec.entry == 0L)
            range(sec.offset, sec.size)
            relocationCount += sec.size / sec.entry
            require(relocationCount <= 500000)
            val syms = symbols(index)
            for (n in 0 until (sec.size / sec.entry).toInt()) {
                val info = u32(sec.offset + n.toLong()*sec.entry+4)
                val sym = syms[(info ushr 8).toInt()]
                if (sym.defined || sym.name.isEmpty()) continue
                val function = (info and 255L) == 22L || sym.type == 2 ||
                    (sym.type != 1 && listOf("pthread_", "__cxa_", "__aeabi_", "__gnu_", "__android_", "dl", "sig", "sem_").any { sym.name.startsWith(it) })
                imports += Import(sym.name, library, sym.bind == 2, function)
            }
        }
        require(sections.none { it.type == 0x60000001L || it.type == 0x60000002L || it.type == 19L }) {
            "Packed relocation format not covered: $library"
        }
        return Image(exports, imports)
    }
}
