package app.morphe.manager.domain.pv7

import android.content.res.AssetManager
import kotlinx.serialization.json.Json

data class Pv7LoadedCatalog(
    val modules: List<Pv7ModuleManifest>,
    val profiles: List<Pv7CompatibilityProfile>,
    val issues: List<String>
)

class Pv7CatalogLoader(
    private val assets: AssetManager,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
) {
    fun load(): Pv7LoadedCatalog {
        val issues = mutableListOf<String>()

        val modules = loadJsonAssets("modules") { path, text ->
            runCatching { json.decodeFromString<Pv7ModuleManifest>(text) }
                .onFailure { issues += "Invalid module " + path + ": " + it.message }
                .getOrNull()
        }

        val profiles = loadJsonAssets("profiles") { path, text ->
            runCatching { json.decodeFromString<Pv7CompatibilityProfile>(text) }
                .onFailure { issues += "Invalid profile " + path + ": " + it.message }
                .getOrNull()
        }

        modules.groupBy { it.id }
            .filterValues { it.size > 1 }
            .keys
            .forEach { issues += "Duplicate module id: " + it }

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

        return Pv7LoadedCatalog(
            modules = modules,
            profiles = profiles,
            issues = issues.distinct()
        )
    }

    fun loadModules(): List<Pv7ModuleManifest> = load().modules

    fun loadProfiles(): List<Pv7CompatibilityProfile> = load().profiles

    private fun <T : Any> loadJsonAssets(
        root: String,
        decode: (path: String, text: String) -> T?
    ): List<T> = listJsonAssets(root).mapNotNull { path ->
        assets.open(path).bufferedReader().use { reader ->
            decode(path, reader.readText())
        }
    }

    private fun listJsonAssets(path: String): List<String> {
        val children = assets.list(path).orEmpty()
        if (children.isEmpty()) {
            return if (path.endsWith(".json", ignoreCase = true)) listOf(path) else emptyList()
        }
        return children.flatMap { child -> listJsonAssets(path + "/" + child) }
    }
}