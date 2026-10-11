package dev.nasexplorer

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private sealed interface Dlg {
    data object NewFolder : Dlg
    data class Rename(val e: NasEntry) : Dlg
    data object Delete : Dlg
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BrowserScreen(vm: AppViewModel) {
    val profile = vm.current ?: return
    val items = remember(vm.entries, vm.sort, vm.sortDesc, vm.showHidden, vm.query, vm.searchActive, vm.deepSearch, vm.deepResults) { vm.visibleEntries() }
    val selecting = vm.selection.isNotEmpty()
    val tasks by vm.transfers.tasks.collectAsState()
    val activeCount = tasks.count { it.active }
    var menu by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var viewMenu by remember { mutableStateOf(false) }
    var dlg by remember { mutableStateOf<Dlg?>(null) }
    LaunchedEffect(vm.navToken) { vm.restoreScroll() }

    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.upload(it) }
    val pickTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { if (it != null) vm.uploadTree(it) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (selecting) {
                        Text("${vm.selection.size} selected")
                    } else if (vm.searchActive) {
                        val focus = remember { FocusRequester() }
                        LaunchedEffect(Unit) { focus.requestFocus() }
                        TextField(
                            value = vm.query,
                            onValueChange = { vm.updateQuery(it) },
                            placeholder = { Text("Search by name") },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                            ),
                            modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        )
                    } else {
                        Column {
                            Text(profile.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "/" + vm.path,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { vm.back() }) {
                        Icon(if (selecting || vm.searchActive) Icons.Default.Close else Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    if (selecting) {
                        TextButton(onClick = { vm.selectAll() }) { Text("All") }
                    } else if (vm.searchActive) {
                        TextButton(onClick = { vm.toggleDeepSearch() }) {
                            Text(if (vm.deepSearch) "✓ Subfolders" else "Subfolders")
                        }
                    } else {
                        IconButton(onClick = { vm.startSearch() }) { Icon(Icons.Default.Search, "Search") }
                        Box {
                            TextButton(onClick = { viewMenu = true }) { Text("▦") }
                            DropdownMenu(expanded = viewMenu, onDismissRequest = { viewMenu = false }) {
                                ViewMode.entries.forEach { m ->
                                    DropdownMenuItem(
                                        text = { Text((if (vm.viewMode == m) "✓  " else "     ") + m.label) },
                                        onClick = { viewMenu = false; vm.chooseViewMode(m) },
                                    )
                                }
                            }
                        }
                        TextButton(onClick = { vm.overlay = Overlay.Transfers }) {
                            Text(if (activeCount > 0) "⇅ $activeCount" else "⇅")
                        }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Menu") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("Refresh") },
                                    onClick = { menu = false; vm.refresh() })
                                DropdownMenuItem(text = { Text("Upload files…") },
                                    onClick = { menu = false; pickFiles.launch(arrayOf("*/*")) })
                                DropdownMenuItem(text = { Text("Upload folder…") },
                                    onClick = { menu = false; pickTree.launch(null) })
                                DropdownMenuItem(text = { Text("New folder") },
                                    onClick = { menu = false; dlg = Dlg.NewFolder })
                                HorizontalDivider()
                                DropdownMenuItem(text = { Text("Sort: ${vm.sort.label}") },
                                    onClick = { menu = false; sortMenu = true })
                                DropdownMenuItem(
                                    text = { Text(if (vm.showHidden) "Hide hidden files" else "Show hidden files") },
                                    onClick = { menu = false; vm.showHidden = !vm.showHidden })
                            }
                            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                                SortMode.entries.forEach { m ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                (if (vm.sort == m) (if (vm.sortDesc) "▼  " else "▲  ") else "      ") +
                                                    m.label
                                            )
                                        },
                                        onClick = { sortMenu = false; vm.sortBy(m) },
                                    )
                                }
                            }
                        }
                    }
                },
            )
        },
        bottomBar = {
            val clip = vm.clip
            if (selecting) {
                BottomAppBar {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        TextButton(onClick = { vm.downloadSelected() }) { Text("⬇ Save") }
                        TextButton(onClick = { vm.copySelection(false) }) { Text("Copy") }
                        TextButton(onClick = { vm.copySelection(true) }) { Text("Cut") }
                        if (vm.selection.size == 1) {
                            TextButton(onClick = { dlg = Dlg.Rename(vm.selected().first()) }) { Text("Rename") }
                        }
                        TextButton(onClick = { dlg = Dlg.Delete }) {
                            Text("Delete", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            } else if (clip != null) {
                BottomAppBar {
                    Text(
                        "${clip.items.size} to ${if (clip.cut) "move" else "copy"}",
                        Modifier.padding(start = 16.dp).weight(1f),
                    )
                    TextButton(onClick = { vm.cancelClip() }) { Text("Cancel") }
                    Button(onClick = { vm.paste() }, modifier = Modifier.padding(end = 16.dp)) { Text("Paste here") }
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            val err = vm.error
            when {
                err != null -> Column(Modifier.padding(24.dp)) {
                    Text("Couldn't load this folder", style = MaterialTheme.typography.titleMedium)
                    Text(err, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { vm.refresh() }) { Text("Retry") }
                }
                !vm.loading && items.isEmpty() && !vm.searching -> Text(
                    if (vm.searchActive && vm.query.isNotBlank()) "No matches" else "Empty folder",
                    Modifier.align(Alignment.Center),
                )
                else -> FileList(vm, items)
            }
            if (vm.loading || vm.searching) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
            vm.busy?.let {
                Text(
                    it,
                    Modifier.align(Alignment.BottomCenter).padding(16.dp)
                        .background(MaterialTheme.colorScheme.inverseSurface)
                        .padding(12.dp),
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                )
            }
        }
    }

    when (val d = dlg) {
        Dlg.NewFolder -> NameDialog("New folder", "", "Create", { dlg = null }) { vm.mkdir(it); dlg = null }
        is Dlg.Rename -> NameDialog("Rename", d.e.name, "Rename", { dlg = null }) { vm.rename(d.e, it); dlg = null }
        Dlg.Delete -> AlertDialog(
            onDismissRequest = { dlg = null },
            title = { Text("Delete ${vm.selection.size} item(s)?") },
            text = { Text("This permanently deletes them from the NAS (folders include everything inside).") },
            confirmButton = {
                TextButton(onClick = { vm.deleteSelected(); dlg = null }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { dlg = null }) { Text("Cancel") } },
        )
        null -> {}
    }
}

@Composable
private fun NameDialog(title: String, initial: String, ok: String, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, singleLine = true) },
        confirmButton = {
            TextButton(enabled = text.isNotBlank() && '/' !in text && '\\' !in text, onClick = { onOk(text.trim()) }) {
                Text(ok)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
