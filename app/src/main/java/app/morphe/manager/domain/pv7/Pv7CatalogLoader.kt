package app.morphe.manager.domain.pv7

import android.content.Context
import android.content.res.AssetManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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
    val pinnedByDefault: Boolean = true
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

class Pv7CatalogLoader(
    private val context: Context,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
) {
    private val assets: AssetManager get() = context.assets
    private val cacheRoot: File get() = File(context.filesDir, "pv7/catalog")

    fun load(refreshRemote: Boolean = true): Pv7LoadedCatalog {
        if (refreshRemote) {
            runCatching {
                refreshRemoteCache()
                return loadDirectory(cacheRoot, Pv7CatalogOrigin.REMOTE)
            }
        }

        if (File(cacheRoot, "index.json").isFile) {
            runCatching { return loadDirectory(cacheRoot, Pv7CatalogOrigin.CACHE) }
        }

        return loadBundled()
    }

    private fun refreshRemoteCache() {
        val indexText = fetchText(OFFICIAL_INDEX_URL)
        val index = json.decodeFromString<Pv7CatalogIndex>(stripBom(indexText))
        require(index.schemaVersion == 1) {
            "Unsupported PV7 catalog schema: " + index.schemaVersion
        }

        val tempRoot = File(context.cacheDir, "pv7-catalog-" + System.nanoTime())
        tempRoot.deleteRecursively()
        tempRoot.mkdirs()
        writeText(File(tempRoot, "index.json"), indexText)

        index.modules.forEach { path ->
            val moduleText = fetchText(OFFICIAL_BASE_URL + path)
            val moduleFile = safeChild(tempRoot, path)
            writeText(moduleFile, moduleText)

            val module = json.decodeFromString<Pv7ModuleManifest>(stripBom(moduleText))
            val moduleDir = path.substringBeforeLast('/')
            module.payloadFiles.forEach { relative ->
                val payloadUrl = OFFICIAL_BASE_URL + moduleDir + "/payload/" + relative
                val payloadPath = moduleDir + "/payload/" + relative
                writeBytes(safeChild(tempRoot, payloadPath), fetchBytes(payloadUrl))
            }
        }

        index.profiles.forEach { path ->
            writeText(safeChild(tempRoot, path), fetchText(OFFICIAL_BASE_URL + path))
        }

        index.apps.forEach { path ->
            writeText(safeChild(tempRoot, path), fetchText(OFFICIAL_BASE_URL + path))
        }

        val validated = loadDirectory(tempRoot, Pv7CatalogOrigin.REMOTE)
        require(validated.issues.isEmpty()) {
            "Downloaded PV7 catalog is invalid: " + validated.issues.joinToString("; ")
        }

        val parent = cacheRoot.parentFile ?: error("PV7 cache parent unavailable")
        parent.mkdirs()
        val backup = File(parent, "catalog-backup")
        backup.deleteRecursively()

        if (cacheRoot.exists()) cacheRoot.renameTo(backup)

        if (!tempRoot.renameTo(cacheRoot)) {
            cacheRoot.deleteRecursively()
            tempRoot.copyRecursively(cacheRoot, overwrite = true)
            tempRoot.deleteRecursively()
        }

        backup.deleteRecursively()
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

        try {
            val code = connection.responseCode
            require(code in 200..299) {
                "HTTP " + code + " fetching " + url
            }
            return connection.inputStream.use { it.readBytes() }
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

    companion object {
        const val OFFICIAL_REPO = "Jacqueb-1337/project-v7"
        const val OFFICIAL_BASE_URL =
            "https://raw.githubusercontent.com/Jacqueb-1337/project-v7/main/catalog/"
        const val OFFICIAL_INDEX_URL = OFFICIAL_BASE_URL + "index.json"
    }
}
