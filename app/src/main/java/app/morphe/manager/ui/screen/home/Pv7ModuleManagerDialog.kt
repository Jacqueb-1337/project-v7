package app.morphe.manager.ui.screen.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.morphe.manager.domain.pv7.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun Pv7ModuleManagerDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val loader = remember(context) { Pv7CatalogLoader(context) }
    val scope = rememberCoroutineScope()
    var catalog by remember { mutableStateOf<Pv7LoadedCatalog?>(null) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var available by remember { mutableStateOf(emptySet<String>()) }
    var sizes by remember { mutableStateOf(emptyMap<String, Long>()) }
    var confirmRemoval by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    suspend fun reload() {
        runCatching {
            withContext(Dispatchers.IO) {
                val loaded = loader.load(false)
                Triple(loaded, loaded.modules.filter(loader::isModuleDownloaded).map { it.id }.toSet(), loaded.modules.associate { it.id to loader.moduleCacheBytes(it) })
            }
        }.onSuccess { (loaded, cached, bytes) -> catalog = loaded; available = cached; sizes = bytes }
            .onFailure { error = it.message }
    }
    LaunchedEffect(Unit) { reload() }
    val modules = catalog?.modules.orEmpty()
    val needed = runCatching { pv7DependencyClosure(modules, selected) }.getOrDefault(emptyList()).filterNot { it.id in available }
    val removable = selected.filter { (sizes[it] ?: 0L) > 0L }.toSet()
    if (confirmRemoval) AlertDialog(
        onDismissRequest = { confirmRemoval = false },
        title = { Text("Remove downloaded modules?") },
        text = { Text("Remove all cached versions of selected modules. Existing patched games are unchanged; future patches may require downloading them again.") },
        confirmButton = { TextButton(onClick = {
            confirmRemoval = false; busy = true; error = null
            scope.launch {
                runCatching { withContext(Dispatchers.IO) { loader.removePayloads(removable) } }.onFailure { error = it.message }
                reload(); busy = false
            }
        }) { Text("Remove") } },
        dismissButton = { TextButton(onClick = { confirmRemoval = false }) { Text("Cancel") } }
    )
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Manage compatibility modules") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (busy) Pv7CatalogStatus()
                OutlinedTextField(query, { query = it }, label = { Text("Search modules") }, singleLine = true)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text("Required downloads: ${needed.size}")
                Text("Cached: " + String.format(java.util.Locale.ROOT, "%.2f MB", sizes.values.sum() / 1000000.0))
                if (removable.isNotEmpty()) OutlinedButton(enabled = !busy, onClick = { confirmRemoval = true }) {
                    Text("Remove selected downloads")
                }
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(modules.filter { it.name.contains(query, true) || it.id.contains(query, true) }, key = { it.id }) { module ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Checkbox(module.id in selected, { checked ->
                                selected = if (checked) selected + module.id else selected - module.id
                            }, enabled = !busy)
                            Column(Modifier.weight(1f)) {
                                Text(module.name)
                                val offline = remember(modules, available, module.id) {
                                    runCatching { pv7DependencyClosure(modules, setOf(module.id)).all { it.id in available } }.getOrDefault(false)
                                }
                                Text(if (offline) "Available offline" else "Download required", style = MaterialTheme.typography.bodySmall)
                                val bytes = sizes[module.id] ?: 0L
                                if (bytes > 0L) Text(String.format(java.util.Locale.ROOT, "%.2f MB cached", bytes / 1000000.0), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = needed.isNotEmpty() && !busy, onClick = {
                busy = true; error = null
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) { loader.ensurePayloads(selected) } }
                        .onSuccess { selected = emptySet() }.onFailure { error = it.message }
                    reload(); busy = false
                }
            }) { Text("Download") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Close") } }
    )
}
