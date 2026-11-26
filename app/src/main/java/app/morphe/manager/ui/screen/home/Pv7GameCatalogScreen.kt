package app.morphe.manager.ui.screen.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.morphe.manager.ui.screen.shared.AppIcon
import app.morphe.manager.R
import app.morphe.manager.domain.pv7.Pv7CatalogLoader
import app.morphe.manager.domain.pv7.Pv7CatalogRefreshPhase
import app.morphe.manager.domain.pv7.Pv7GameCatalogSearch
import app.morphe.manager.domain.pv7.Pv7SupportedApp

@Composable
fun Pv7GameCatalogScreen(
    apps: Collection<Pv7SupportedApp>,
    loading: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onPickApk: () -> Unit,
    onGameClick: (Pv7SupportedApp) -> Unit
) {
    val progress by Pv7CatalogLoader.refreshProgress.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    val results = remember(apps, query) { Pv7GameCatalogSearch.search(apps, query) }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, stringResource(R.string.pv7_game_catalog_back)) }
            Text(stringResource(R.string.pv7_game_catalog_title), style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f))
            IconButton(onClick = onRefresh, enabled = progress?.active != true) { Icon(Icons.Outlined.Refresh, stringResource(R.string.pv7_game_catalog_refresh)) }
        }
        progress?.let { state ->
            if (state.active || state.phase == Pv7CatalogRefreshPhase.FAILED) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val label = when (state.phase) {
                        Pv7CatalogRefreshPhase.INDEX -> "Checking catalog"
                        Pv7CatalogRefreshPhase.MODULES -> "Modules: ${state.completed} / ${state.total}"
                        Pv7CatalogRefreshPhase.PROFILES -> "Profiles: ${state.completed} / ${state.total}"
                        Pv7CatalogRefreshPhase.APPS -> "Games: ${state.completed} / ${state.total}"
                        Pv7CatalogRefreshPhase.VALIDATING -> "Validating catalog"
                        Pv7CatalogRefreshPhase.APPLYING -> "Applying catalog"
                        Pv7CatalogRefreshPhase.FAILED -> "Refresh failed. Showing available catalog."
                        else -> "Catalog updated"
                    }
                    Text(label, style = MaterialTheme.typography.labelMedium)
                    if (state.active) {
                        if (state.item.isNotBlank()) Text(state.item, style = MaterialTheme.typography.bodySmall)
                        if (state.total > 0) {
                            LinearProgressIndicator(progress = { state.completed.toFloat().div(state.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        }
                        val received = String.format(java.util.Locale.getDefault(), "%.2f MB", state.bytes / 1048576.0)
                        if (state.totalBytes > 0) {
                            val fraction = (state.bytes.toFloat() / state.totalBytes).coerceIn(0f, 1f)
                            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                            Text(received + " / " + String.format(java.util.Locale.getDefault(), "%.2f MB", state.totalBytes / 1048576.0) + " (" + (fraction * 100).toInt() + "%)", style = MaterialTheme.typography.bodySmall)
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            if (state.bytes > 0) Text(received, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text(stringResource(R.string.pv7_game_catalog_search)) },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) {
                Icon(Icons.Outlined.Close, stringResource(R.string.pv7_game_catalog_clear))
            } }
        )
        Text(stringResource(R.string.pv7_game_catalog_count, results.size, apps.size),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (loading) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (results.isEmpty()) item {
                    Text(stringResource(if (apps.isEmpty()) R.string.pv7_game_catalog_empty else R.string.pv7_game_catalog_no_results))
                }
                items(results, key = { it.packageName }) { entry ->
                    Card(Modifier.fillMaxWidth().clickable { onGameClick(entry) }) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                            AppIcon(packageName = entry.packageName, remoteIconUrl = entry.iconUrl,
                                contentDescription = null, modifier = Modifier.size(56.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(entry.displayName, style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.weight(1f))
                                if (entry.experimental) Text(stringResource(R.string.pv7_game_catalog_experimental),
                                    color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.labelMedium)
                                else if (entry.gameplayTested) Text(stringResource(R.string.pv7_game_catalog_supported),
                                    color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                            }
                            Text(entry.packageName, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val detail = listOfNotNull(entry.engine, entry.recommendedVersion?.let { "v$it" }).joinToString("  |  ")
                            if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodyMedium)
                            entry.notes?.takeIf { it.isNotBlank() }?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            }
                        }
                    }
                }
            }
        }
        OutlinedButton(onClick = onPickApk, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(stringResource(R.string.pv7_game_catalog_other_apk))
        }
    }
}
