package dev.nasexplorer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransfersScreen(vm: AppViewModel) {
    val tasks by vm.transfers.tasks.collectAsState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Transfers") },
                navigationIcon = {
                    IconButton(onClick = { vm.overlay = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = { TextButton(onClick = { vm.transfers.clearFinished() }) { Text("Clear finished") } },
            )
        },
    ) { pad ->
        if (tasks.isEmpty()) {
            Column(Modifier.padding(pad).fillMaxSize(), verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text("No transfers yet")
                Text("Files you save to the phone are in Downloads/NAS.",
                    style = MaterialTheme.typography.bodySmall)
            }
        } else {
            LazyColumn(Modifier.padding(pad), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                items(tasks.reversed(), key = { it.id }) { t ->
                    Column(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${t.kind}: ${t.title}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val detail = when (t.state) {
                                    TState.Queued -> "Waiting…"
                                    TState.Running ->
                                        if (t.total > 0) "${humanSize(t.done)} of ${humanSize(t.total)}" else "Working…"
                                    TState.Done -> "Done" + if (t.total > 0) " · ${humanSize(t.total)}" else ""
                                    TState.Failed -> "Failed: ${t.error}"
                                    TState.Cancelled -> "Cancelled"
                                }
                                Text(
                                    detail,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (t.state == TState.Failed) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (t.active) TextButton(onClick = { vm.transfers.cancel(t.id) }) { Text("Cancel") }
                        }
                        if (t.state == TState.Running) {
                            if (t.total > 0) {
                                LinearProgressIndicator(
                                    progress = { (t.done.toFloat() / t.total).coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            } else {
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
            }
        }
    }
}
