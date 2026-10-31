package app.morphe.manager.domain.pv7

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Persisted in every new PV7 wrapper to support exact, repeatable module upgrades. */
@Serializable
data class Pv7LockedModule(
    val id: String,
    val version: String,
    val kind: Pv7ModuleKind
)

@Serializable
data class Pv7ModuleLock(
    val schemaVersion: Int = 1,
    val guestPackageName: String,
    val guestVersionName: String? = null,
    val guestEngineId: String? = null,
    val guestEngineVersion: String? = null,
    val guestAbis: List<String> = emptyList(),
    val embeddedGuestPath: String? = null,
    val embeddedGuestSha256: String? = null,
    val modules: List<Pv7LockedModule> = emptyList()
)

/** A version change is never applied automatically to an installed game. */
data class Pv7ModuleUpgrade(
    val moduleId: String,
    val installedVersion: String,
    val availableVersion: String,
    val kind: Pv7ModuleKind
)

object Pv7ModuleUpgradeAdvisor {
    fun available(lock: Pv7ModuleLock, catalog: Collection<Pv7ModuleManifest>): List<Pv7ModuleUpgrade> {
        val available = catalog.associateBy { it.id }
        return lock.modules.mapNotNull { previous ->
            val latest = available[previous.id] ?: return@mapNotNull null
            if (Pv7ModuleVersions.compare(latest.version, previous.version) <= 0) return@mapNotNull null
            Pv7ModuleUpgrade(previous.id, previous.version, latest.version, latest.kind)
        }
    }
}

/**
 * Recover an original guest APK only when that exact copy was deliberately
 * embedded by PV7. A wrapper that predates the lockfile cannot be repatched
 * this way and must ask the user for the original APK again.
 */
object Pv7EmbeddedGuest {
    const val LOCK_PATH = "assets/pv7/module-lock.json"
    const val GUEST_PATH = "assets/pv7/original-guest.apk"
    private val json = Json { ignoreUnknownKeys = true }

    fun readLock(wrapperApk: File): Pv7ModuleLock? =
        ZipFile(wrapperApk).use { zip ->
            val entry = zip.getEntry(LOCK_PATH) ?: return null
            require(entry.size in 1..262_144L) { "Invalid PV7 module-lock size" }
            val lock = zip.getInputStream(entry).bufferedReader().use { json.decodeFromString<Pv7ModuleLock>(it.readText()) }
            require(lock.schemaVersion == 1) { "Unsupported PV7 module-lock schema" }
            lock
        }

    fun extractOriginal(wrapperApk: File, destination: File): Pv7ModuleLock {
        val lock = readLock(wrapperApk) ?: throw IOException(
            "This wrapper predates embedded APK support. Select the original game APK to repatch."
        )
        if (lock.embeddedGuestPath != GUEST_PATH || lock.embeddedGuestSha256.isNullOrBlank())
            throw IOException("This PV7 wrapper does not contain a recoverable original game APK.")
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, destination.name + ".part")
        try {
            ZipFile(wrapperApk).use { zip ->
                val entry = zip.getEntry(GUEST_PATH)
                    ?: throw IOException("Embedded original game APK is missing")
                if (entry.size <= 0 || entry.size > 2L * 1024 * 1024 * 1024)
                    throw IOException("Embedded original APK exceeds safe size limits")
                val digest = MessageDigest.getInstance("SHA-256")
                var length = 0L
                zip.getInputStream(entry).use { input ->
                    FileOutputStream(temporary).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            length += read
                            if (length > 2L * 1024 * 1024 * 1024)
                                throw IOException("Embedded guest extraction size limit exceeded")
                            digest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                        }
                    }
                }
                val checksum = digest.digest().joinToString("") { "%02x".format(it) }
                if (!checksum.equals(lock.embeddedGuestSha256, ignoreCase = true))
                    throw IOException("The embedded original game APK failed SHA-256 verification")
            }
            ZipFile(temporary).use { zip ->
                if (zip.getEntry("AndroidManifest.xml") == null)
                    throw IOException("Embedded guest is not a valid Android APK")
            }
            if (destination.exists() && !destination.delete())
                throw IOException("Cannot replace recovered guest APK")
            if (!temporary.renameTo(destination))
                throw IOException("Could not finalize recovered guest APK")
            return lock
        } finally {
            temporary.delete()
        }
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = stream.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun lockFor(config: Pv7WrapperConfig, modules: List<Pv7ModuleManifest>,
                originalGuestApk: File?): Pv7ModuleLock = Pv7ModuleLock(
        guestPackageName = config.guestPackageName ?: config.packageName,
        guestVersionName = config.versionName,
        guestEngineId = originalGuestApk?.let { Pv7EngineDetector.detect(it)?.id },
        guestEngineVersion = originalGuestApk?.let { Pv7EngineDetector.detect(it)?.version },
        guestAbis = originalGuestApk?.let { Pv7EngineDetector.detectAbis(it).sorted() } ?: emptyList(),
        embeddedGuestPath = originalGuestApk?.let { GUEST_PATH },
        embeddedGuestSha256 = originalGuestApk?.let(::sha256),
        modules = modules.sortedBy { it.id }.map { Pv7LockedModule(it.id, it.version, it.kind) }
    )

    fun encode(lock: Pv7ModuleLock): String = json.encodeToString(Pv7ModuleLock.serializer(), lock)
}
