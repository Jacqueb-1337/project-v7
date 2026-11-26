package app.morphe.manager.domain.pv7

import java.util.Locale

object Pv7GameCatalogSearch {
    fun search(apps: Collection<Pv7SupportedApp>, query: String): List<Pv7SupportedApp> {
        val tokens = query.trim().lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotEmpty() }
        return apps.asSequence()
            .distinctBy { it.packageName }
            .filter { app ->
                val searchable = listOf(app.displayName, app.packageName, app.engine.orEmpty(),
                    app.recommendedVersion.orEmpty(), app.versions.joinToString(" "),
                    if (app.experimental) "experimental" else "").joinToString(" ").lowercase(Locale.ROOT)
                tokens.all { it in searchable }
            }
            .sortedWith(compareBy<Pv7SupportedApp> { it.displayName.lowercase(Locale.ROOT) }.thenBy { it.packageName })
            .toList()
    }

    fun showOnHome(packageName: String, catalogPackages: Set<String>, installed: Boolean, tracked: Boolean): Boolean =
        packageName !in catalogPackages || installed || tracked
}
