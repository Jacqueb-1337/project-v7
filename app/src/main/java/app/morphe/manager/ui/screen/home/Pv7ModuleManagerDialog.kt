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
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    suspend fun reload() {
        runCatching {
            withContext(Dispatchers.IO) {
                val loaded = loader.load(false)
                loaded to loaded.modules.filter(loader::isModuleDownloaded).map { it.id }.toSet()
            }
        }.onSuccess { (loaded, cached) -> catalog = loaded; available = cached }
            .onFailure { error = it.message }
    }
    LaunchedEffect(Unit) { reload() }
    val modules = catalog?.modules.orEmpty()
    val needed = runCatching { pv7DependencyClosure(modules, selected) }.getOrDefault(emptyList()).filterNot { it.id in available }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Manage compatibility modules") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (busy) Pv7CatalogStatus()
                OutlinedTextField(query, { query = it }, label = { Text("Search modules") }, singleLine = true)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text("Required downloads: ${needed.size}")
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
