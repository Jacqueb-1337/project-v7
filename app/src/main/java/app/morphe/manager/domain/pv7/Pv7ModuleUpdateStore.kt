package app.morphe.manager.domain.pv7

import android.content.Context
import kotlinx.serialization.json.Json

/** Persist wrappers built by this installation, for module update checks on reopen. */
class Pv7ModuleUpdateStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "pv7_wrapper_module_versions", Context.MODE_PRIVATE
    )
    private val json = Json { ignoreUnknownKeys = true }

    fun record(wrapperPackage: String, lock: Pv7ModuleLock) {
        require(wrapperPackage.matches(Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")))
        preferences.edit().putString(wrapperPackage, json.encodeToString(Pv7ModuleLock.serializer(), lock)).apply()
    }

    fun builds(): Map<String, Pv7ModuleLock> =
        preferences.all.mapNotNull { (key, value) ->
            val lock = runCatching {
                json.decodeFromString<Pv7ModuleLock>(value as? String ?: return@runCatching null)
            }.getOrNull() ?: return@mapNotNull null
            if (lock.schemaVersion != 1) return@mapNotNull null
            key to lock
        }.toMap()

    fun available(catalog: Collection<Pv7ModuleManifest>): Map<String, List<Pv7ModuleUpgrade>> =
        builds().mapValues { (_, lock) -> Pv7ModuleUpgradeAdvisor.available(lock, catalog) }
            .filterValues(List<Pv7ModuleUpgrade>::isNotEmpty)
}
