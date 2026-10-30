package app.morphe.manager.ui.screen.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import app.morphe.manager.domain.pv7.Pv7CatalogLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Official Project V7 package names.
 *
 * This is used only to trim the inherited default Morphe source. Custom sources are intentionally
 * unrestricted and may introduce apps that do not exist in the official Project V7 catalog.
 */
@Composable
internal fun rememberPv7OfficialPackageNames(): Set<String> {
    val context = LocalContext.current.applicationContext
    val packages by produceState<Set<String>>(initialValue = emptySet(), context) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                Pv7CatalogLoader(context)
                    .load(refreshRemote = false)
                    .apps
                    .mapTo(mutableSetOf()) { it.packageName }
            }.getOrDefault(emptySet())
        }
    }
    return packages
}
