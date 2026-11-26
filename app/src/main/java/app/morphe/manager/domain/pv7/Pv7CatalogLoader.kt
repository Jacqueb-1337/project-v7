package app.morphe.manager.domain.pv7

import android.content.Context
import android.content.res.AssetManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

@Serializable
data class Pv7SupportedApp(
    val packageName: String,
    val displayName: String,
    val recommendedVersion: String? = null,
    val versions: List<String> = emptyList(),
    val downloadUrl: String? = null,
    val iconUrl: String? = null,
    val pinnedByDefault: Boolean = false,
    val engine: String? = null,
    val experimental: Boolean = false,
    val gameplayTested: Boolean = false,
    val notes: String? = null
)

@Serializable
data class Pv7CatalogIndex(
    val schemaVersion: Int = 1,
    val modules: List<String> = emptyList(),
    val profiles: List<String> = emptyList(),
    val apps: List<String> = emptyList()
)

enum class Pv7CatalogOrigin {
    REMOTE,
    CACHE,
    BUNDLED
}

data class Pv7LoadedCatalog(
    val modules: List<Pv7ModuleManifest>,
    val profiles: List<Pv7CompatibilityProfile>,
    val apps: List<Pv7SupportedApp>,
    val issues: List<String>,
    val origin: Pv7CatalogOrigin
)

enum class Pv7CatalogRefreshPhase { INDEX, MODULES, PROFILES, APPS, VALIDATING, APPLYING, COMPLETE, FAILED }

data class Pv7CatalogRefreshProgress(
    val phase: Pv7CatalogRefreshPhase,
    val item: String = "",
    val completed: Int = 0,
    val total: Int = 0,
    val bytes: Long = 0,
    val totalBytes: Long = -1,
    val checkedAtMillis: Long = 0,
    val changed: Boolean = false
) {
    val active get() = phase != Pv7CatalogRefreshPhase.COMPLETE && phase != Pv7CatalogRefreshPhase.FAILED
}

class Pv7CatalogLoader(
    private val context: Context,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
) {
    private val assets: AssetManager get() = context.assets
    private val preferences get() =
        context.getSharedPreferences("pv7_catalog_sources", Context.MODE_PRIVATE)
    val repository: String
        get() = preferences.getString("repository", null)
            ?.let { runCatching { Pv7CatalogRepository.validate(it) }.getOrNull() }
            ?: Pv7CatalogRepository.OFFICIAL
    private fun cacheRootFor(repo: String): File
        = File(context.filesDir, "pv7/catalog-" + repo.replace('/', '_'))

    /** Explicitly chosen by the user when testing a contributor's fork. */
    fun setRepository(repo: String) {
        preferences.edit()
            .putString("repository", Pv7CatalogRepository.validate(repo))
            .apply()
    }

    fun load(refreshRemote: Boolean = true): Pv7LoadedCatalog {
        val source = repository
        val cacheRoot = cacheRootFor(source)
        if (!refreshRemote && _refreshProgress.value == null) {
            val checked = preferences.getLong("checked_at/$source", 0)
            val result = preferences.getString("checked_result/$source", null)
            if (checked > 0 && result != null) {
                val phase = if (result == "failed") Pv7CatalogRefreshPhase.FAILED else Pv7CatalogRefreshPhase.COMPLETE
                _refreshProgress.compareAndSet(null, Pv7CatalogRefreshProgress(
                    phase, checkedAtMillis = checked, changed = result == "changed"))
            }
        }
        if (refreshRemote) {
            runCatching {
                synchronized(refreshLock) {
                    try {
                        val changed = refreshRemoteCache(source, cacheRoot)
                        _refreshProgress.value = Pv7CatalogRefreshProgress(Pv7CatalogRefreshPhase.COMPLETE,
                            checkedAtMillis = System.currentTimeMillis(), changed = changed)
                        preferences.edit().putLong("checked_at/$source", System.currentTimeMillis())
                            .putString("checked_result/$source", if (changed) "changed" else "unchanged").apply()
                    } catch (e: Exception) {
                        _refreshProgress.value = Pv7CatalogRefreshProgress(Pv7CatalogRefreshPhase.FAILED, checkedAtMillis = System.currentTimeMillis())
                        preferences.edit().putLong("checked_at/$source", System.currentTimeMillis())
                            .putString("checked_result/$source", "failed").apply()
                        throw e
                    }
                }
                return loadDirectory(cacheRoot, Pv7CatalogOrigin.REMOTE)
            }
        }

        if (File(cacheRoot, "index.json").isFile) {
            runCatching { return loadDirectory(cacheRoot, Pv7CatalogOrigin.CACHE) }
        }

        return loadBundled()
    }

    private fun refreshRemoteCache(source: String, cacheRoot: File): Boolean {
        val baseUrl = Pv7CatalogRepository.baseUrl(source)
        report(Pv7CatalogRefreshPhase.INDEX, "index.json")
        val indexText = fetchText(baseUrl + "index.json")
        val index = json.decodeFromString<Pv7CatalogIndex>(stripBom(indexText))
        require(index.schemaVersion == 1) {
            "Unsupported PV7 catalog schema: " + index.schemaVersion
        }

        val parent = cacheRoot.parentFile ?: error("PV7 cache parent unavailable")
        parent.mkdirs()
        val backup = File(parent, cacheRoot.name + ".backup")
        if (backup.exists() && !cacheRoot.exists()) {
            check(backup.renameTo(cacheRoot)) { "Cannot recover catalog cache" }
        }
        val tempRoot = File(parent, cacheRoot.name + ".incoming-" + System.nanoTime())
        check(tempRoot.mkdirs()) { "Cannot create catalog staging directory" }
        try {
            var changed = !File(cacheRoot, "index.json").isFile || File(cacheRoot, "index.json").readText() != indexText
            fun cacheText(path: String, value: String) {
                val previous = safeChild(cacheRoot, path)
                if (!previous.isFile || previous.readText() != value) changed = true
                writeText(safeChild(tempRoot, path), value)
            }
            writeText(File(tempRoot, "index.json"), indexText)

            index.modules.forEachIndexed { moduleNumber, path ->
                report(Pv7CatalogRefreshPhase.MODULES, path.substringBeforeLast('/').substringAfterLast('/'), moduleNumber, index.modules.size)
                val moduleText = fetchText(baseUrl + path)
                cacheText(path, moduleText)

                val module = json.decodeFromString<Pv7ModuleManifest>(stripBom(moduleText))
                val moduleDir = path.substringBeforeLast('/')
                // A versioned module manifest is the cache key. Reuse unchanged
                // payload bytes rather than downloading large native libraries on
                // every catalog refresh. Changing a payload requires a version bump.
                val cachedManifest = safeChild(cacheRoot, path)
                val mayReusePayload = cachedManifest.isFile &&
                    cachedManifest.readText() == moduleText
                module.payloadFiles.forEach { relative ->
                    val payloadUrl = baseUrl + moduleDir + "/payload/" + relative
                    val payloadPath = moduleDir + "/payload/" + relative
                    val output = safeChild(tempRoot, payloadPath)
                    val existing = safeChild(cacheRoot, payloadPath)
                    if (mayReusePayload && existing.isFile) {
                        output.parentFile?.mkdirs()
                        existing.copyTo(output, overwrite = true)
                    } else {
                        report(Pv7CatalogRefreshPhase.MODULES, module.name + " / " + relative.substringAfterLast('/'), moduleNumber, index.modules.size)
                        changed = true
                        writeBytes(output, fetchBytes(payloadUrl))
                    }
                }
            }

            index.profiles.forEachIndexed { i, path ->
                report(Pv7CatalogRefreshPhase.PROFILES, path, i, index.profiles.size)
                cacheText(path, fetchText(baseUrl + path))
            }

            index.apps.forEachIndexed { i, path ->
                report(Pv7CatalogRefreshPhase.APPS, path, i, index.apps.size)
                cacheText(path, fetchText(baseUrl + path))
            }

            report(Pv7CatalogRefreshPhase.VALIDATING)
            val validated = loadDirectory(tempRoot, Pv7CatalogOrigin.REMOTE)
            require(validated.issues.isEmpty()) {
                "Downloaded PV7 catalog is invalid: " + validated.issues.joinToString("; ")
            }

            report(Pv7CatalogRefreshPhase.APPLYING)
            check(!backup.exists() || backup.deleteRecursively()) { "Cannot remove previous catalog backup" }
            if (cacheRoot.exists()) {
                check(cacheRoot.renameTo(backup)) { "Cannot preserve old catalog" }
            }
            if (!tempRoot.renameTo(cacheRoot)) {
                check(!backup.exists() || backup.renameTo(cacheRoot)) { "Cannot restore previous catalog" }
                error("Cannot apply new catalog")
            }
            backup.deleteRecursively()
            return changed
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    private fun loadDirectory(root: File, origin: Pv7CatalogOrigin): Pv7LoadedCatalog {
        val index = json.decodeFromString<Pv7CatalogIndex>(
            stripBom(File(root, "index.json").readText())
        )
        val issues = mutableListOf<String>()

        val modules = index.modules.mapNotNull { path ->
            val file = safeChild(root, path)
            runCatching {
                json.decodeFromString<Pv7ModuleManifest>(stripBom(file.readText())).copy(
                    sourceAssetPath = "file:" + file.parentFile!!.absolutePath
                )
            }.onFailure {
                issues += "Invalid module " + path + ": " + it.message
            }.getOrNull()
        }

        val profiles = index.profiles.mapNotNull { path ->
            val file = safeChild(root, path)
            runCatching {
                json.decodeFromString<Pv7CompatibilityProfile>(stripBom(file.readText()))
            }.onFailure {
                issues += "Invalid profile " + path + ": " + it.message
            }.getOrNull()
        }

        val apps = index.apps.mapNotNull { path ->
            val file = safeChild(root, path)
            runCatching {
                json.decodeFromString<Pv7SupportedApp>(stripBom(file.readText()))
            }.onFailure {
                issues += "Invalid app " + path + ": " + it.message
            }.getOrNull()
        }

        validate(modules, profiles, apps, issues)
        return Pv7LoadedCatalog(
            modules = modules,
            profiles = profiles,
            apps = apps,
            issues = issues.distinct(),
            origin = origin
        )
    }

    private fun loadBundled(): Pv7LoadedCatalog {
        val issues = mutableListOf<String>()

        val index = runCatching {
            assets.open("index.json").bufferedReader().use {
                json.decodeFromString<Pv7CatalogIndex>(stripBom(it.readText()))
            }
        }.getOrElse {
            return loadBundledLegacy()
        }

        val modules = index.modules.mapNotNull { path ->
            runCatching {
                assets.open(path).bufferedReader().use { reader ->
                    json.decodeFromString<Pv7ModuleManifest>(stripBom(reader.readText())).copy(
                        sourceAssetPath = "asset:" + path.substringBeforeLast('/')
                    )
                }
            }.onFailure {
                issues += "Invalid module " + path + ": " + it.message
            }.getOrNull()
        }

        val profiles = index.profiles.mapNotNull { path ->
            runCatching {
                assets.open(path).bufferedReader().use { reader ->
                    json.decodeFromString<Pv7CompatibilityProfile>(stripBom(reader.readText()))
                }
            }.onFailure {
                issues += "Invalid profile " + path + ": " + it.message
            }.getOrNull()
        }

        val apps = index.apps.mapNotNull { path ->
            runCatching {
                assets.open(path).bufferedReader().use { reader ->
                    json.decodeFromString<Pv7SupportedApp>(stripBom(reader.readText()))
                }
            }.onFailure {
                issues += "Invalid app " + path + ": " + it.message
            }.getOrNull()
        }

        validate(modules, profiles, apps, issues)
        return Pv7LoadedCatalog(
            modules = modules,
            profiles = profiles,
            apps = apps,
            issues = issues.distinct(),
            origin = Pv7CatalogOrigin.BUNDLED
        )
    }

    private fun loadBundledLegacy(): Pv7LoadedCatalog {
        val issues = mutableListOf<String>()

        val modules = loadJsonAssets("modules") { path, text ->
            runCatching {
                json.decodeFromString<Pv7ModuleManifest>(stripBom(text)).copy(
                    sourceAssetPath =
                        "asset:" + path.substringBeforeLast(
                            "/module.json",
                            path.substringBeforeLast('/')
                        )
                )
            }.onFailure {
                issues += "Invalid module " + path + ": " + it.message
            }.getOrNull()
        }

        val profiles = loadJsonAssets("profiles") { path, text ->
            runCatching {
                json.decodeFromString<Pv7CompatibilityProfile>(stripBom(text))
            }.onFailure {
                issues += "Invalid profile " + path + ": " + it.message
            }.getOrNull()
        }

        validate(modules, profiles, emptyList(), issues)
        return Pv7LoadedCatalog(
            modules = modules,
            profiles = profiles,
            apps = emptyList(),
            issues = issues.distinct(),
            origin = Pv7CatalogOrigin.BUNDLED
        )
    }

    private fun validate(
        modules: List<Pv7ModuleManifest>,
        profiles: List<Pv7CompatibilityProfile>,
        apps: List<Pv7SupportedApp>,
        issues: MutableList<String>
    ) {
        modules.groupBy { it.id }
            .filterValues { it.size > 1 }
            .keys
            .forEach { issues += "Duplicate module id: " + it }

        apps.groupBy { it.packageName }
            .filterValues { it.size > 1 }
            .keys
            .forEach { issues += "Duplicate supported app package: " + it }

        val moduleIds = modules.mapTo(hashSetOf()) { it.id }

        modules.forEach { module ->
            module.dependencies.filterNot(moduleIds::contains).forEach { missing ->
                issues += "Module " + module.id + " depends on missing module " + missing
            }
        }

        profiles.forEach { profile ->
            profile.recommendedModules.filterNot(moduleIds::contains).forEach { missing ->
                issues += "Profile " + profile.id + " recommends missing module " + missing
            }
        }
    }

    private fun fetchText(url: String): String =
        fetchBytes(url).toString(Charsets.UTF_8)

    private fun fetchBytes(url: String): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 5000
        connection.readTimeout = 10000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "Project-V7-Patcher")
        connection.setRequestProperty("Accept-Encoding", "identity")

        try {
            val code = connection.responseCode
            require(code in 200..299) {
                "HTTP " + code + " fetching " + url
            }
            val total = connection.contentLengthLong
            _refreshProgress.value = _refreshProgress.value?.copy(bytes = 0, totalBytes = total)
            val progress = _refreshProgress.value
            val out = ByteArrayOutputStream()
            var received = 0L
            var lastUpdate = 0L
            connection.inputStream.use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    received += n
                    val now = System.nanoTime()
                    if (now - lastUpdate >= 100_000_000L) {
                        _refreshProgress.value = progress?.copy(bytes = received, totalBytes = total)
                        lastUpdate = now
                    }
                }
            }
            _refreshProgress.value = progress?.copy(bytes = received, totalBytes = total)
            return out.toByteArray()
        } finally {
            connection.disconnect()
        }
    }

    private fun writeText(file: File, text: String) =
        writeBytes(file, text.toByteArray(Charsets.UTF_8))

    private fun writeBytes(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
    }

    private fun safeChild(root: File, relative: String): File {
        val file = File(root, relative)
        require(file.canonicalPath.startsWith(root.canonicalPath + File.separator)) {
            "Unsafe catalog path: " + relative
        }
        return file
    }

    private fun stripBom(text: String): String =
        if (text.isNotEmpty() && text[0] == '\uFEFF') text.substring(1) else text

    private fun <T : Any> loadJsonAssets(
        root: String,
        decode: (path: String, text: String) -> T?
    ): List<T> =
        listJsonAssets(root).mapNotNull { path ->
            assets.open(path).bufferedReader().use { reader ->
                decode(path, reader.readText())
            }
        }

    private fun listJsonAssets(path: String): List<String> {
        val children = assets.list(path).orEmpty()
        if (children.isEmpty()) {
            return if (path.endsWith(".json", ignoreCase = true)) {
                listOf(path)
            } else {
                emptyList()
            }
        }

        return children.flatMap { child ->
            listJsonAssets(path + "/" + child)
        }
    }

    private fun report(phase: Pv7CatalogRefreshPhase, item: String = "", completed: Int = 0, total: Int = 0) {
        _refreshProgress.value = Pv7CatalogRefreshProgress(phase, item, completed, total)
    }

    companion object {
        private val refreshLock = Any()
        private val _refreshProgress = MutableStateFlow<Pv7CatalogRefreshProgress?>(null)
        val refreshProgress = _refreshProgress.asStateFlow()
        const val OFFICIAL_REPO = "Jacqueb-1337/project-v7"
        const val OFFICIAL_BASE_URL =
            "https://raw.githubusercontent.com/Jacqueb-1337/project-v7/main/catalog/"
        const val OFFICIAL_INDEX_URL = OFFICIAL_BASE_URL + "index.json"
    }
}
