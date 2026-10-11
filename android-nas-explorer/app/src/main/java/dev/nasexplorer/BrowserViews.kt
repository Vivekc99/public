package dev.nasexplorer

import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.DateFormat
import java.util.Date

enum class ViewMode(val label: String) {
    Simple("List"),
    Details("Details"),
    SmallGrid("Small thumbnails"),
    LargeGrid("Large thumbnails"),
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.itemClick(vm: AppViewModel, e: NasEntry) = combinedClickable(
    onClick = { if (vm.selection.isNotEmpty()) vm.toggle(e) else vm.open(e) },
    onLongClick = { vm.toggle(e) },
)

private fun AppViewModel.shownDate(e: NasEntry) = if (sort == SortMode.Created) e.created else e.modified

private fun shortDate(ms: Long): String = DateFormat.getDateInstance(DateFormat.SHORT).format(Date(ms))

/** Picture preview for photos/videos, otherwise the file-type emoji. */
@Composable
fun Thumb(vm: AppViewModel, e: NasEntry, modifier: Modifier, emojiSize: TextUnit) {
    val profile = vm.current
    val kind = if (e.isDir) Kind.Other else kindOf(e.name)
    val bmp by produceState<Bitmap?>(null, e.path, e.modified) {
        if (profile != null && (kind == Kind.Image || kind == Kind.Video)) value = vm.thumbs.load(profile, e)
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) {
            Image(b.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            if (kind == Kind.Video) {
                Text("▶", color = Color.White, fontSize = emojiSize,
                    modifier = Modifier.background(Color(0x66000000), CircleShape).padding(horizontal = 6.dp))
            }
        } else {
            Text(iconFor(e), fontSize = emojiSize)
        }
    }
}

@Composable
fun FileList(vm: AppViewModel, items: List<NasEntry>, modifier: Modifier = Modifier) {
    when (vm.viewMode) {
        ViewMode.Simple -> SimpleList(vm, items, modifier)
        ViewMode.Details -> DetailsList(vm, items, modifier)
        ViewMode.SmallGrid -> Grid(vm, items, 92.dp, 34.sp, modifier)
        ViewMode.LargeGrid -> Grid(vm, items, 160.dp, 56.sp, modifier)
    }
}

@Composable
private fun SimpleList(vm: AppViewModel, items: List<NasEntry>, modifier: Modifier) {
    val selecting = vm.selection.isNotEmpty()
    LazyColumn(modifier.fillMaxSize(), state = vm.listState) {
        items(items, key = { it.path }) { e ->
            val sel = e.path in vm.selection
            Row(
                Modifier.fillMaxWidth()
                    .background(if (sel) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                    .itemClick(vm, e)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Thumb(vm, e, Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)), 26.sp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        (if (vm.searchActive && vm.deepSearch) "/${parentPath(e.path)}  ·  " else "") +
                            (if (e.isDir) "" else humanSize(e.size) + " · ") + humanDate(vm.shownDate(e)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (selecting) Checkbox(checked = sel, onCheckedChange = null)
            }
        }
    }
}

@Composable
private fun DetailsList(vm: AppViewModel, items: List<NasEntry>, modifier: Modifier) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
            Spacer(Modifier.width(34.dp))
            val arrow = if (vm.sortDesc) " ▼" else " ▲"
            fun label(m: SortMode, text: String) = text + if (vm.sort == m) arrow else ""
            Text(label(SortMode.Name, "Name"), Modifier.weight(1f).clickable { vm.sortBy(SortMode.Name) },
                style = MaterialTheme.typography.labelMedium, color = dim)
            Text(label(SortMode.Size, "Size"), Modifier.width(76.dp).clickable { vm.sortBy(SortMode.Size) },
                style = MaterialTheme.typography.labelMedium, color = dim, textAlign = TextAlign.End)
            val dateMode = if (vm.sort == SortMode.Created) SortMode.Created else SortMode.Modified
            Text(label(dateMode, if (dateMode == SortMode.Created) "Created" else "Modified"),
                Modifier.width(84.dp).clickable { vm.sortBy(dateMode) },
                style = MaterialTheme.typography.labelMedium, color = dim, textAlign = TextAlign.End)
        }
        HorizontalDivider()
        LazyColumn(Modifier.fillMaxSize(), state = vm.listState) {
            items(items, key = { it.path }) { e ->
                val sel = e.path in vm.selection
                Row(
                    Modifier.fillMaxWidth()
                        .background(if (sel) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                        .itemClick(vm, e)
                        .padding(horizontal = 16.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(iconFor(e), fontSize = 20.sp, modifier = Modifier.width(34.dp))
                    Column(Modifier.weight(1f)) {
                        Text(e.name, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium)
                        if (vm.searchActive && vm.deepSearch) {
                            Text("/${parentPath(e.path)}", maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelSmall, color = dim)
                        }
                    }
                    Text(if (e.isDir) "" else humanSize(e.size), Modifier.width(76.dp),
                        style = MaterialTheme.typography.bodySmall, color = dim, textAlign = TextAlign.End)
                    Text(shortDate(vm.shownDate(e)), Modifier.width(84.dp),
                        style = MaterialTheme.typography.bodySmall, color = dim, textAlign = TextAlign.End)
                }
            }
        }
    }
}

@Composable
private fun Grid(vm: AppViewModel, items: List<NasEntry>, cell: Dp, emoji: TextUnit, modifier: Modifier) {
    val selecting = vm.selection.isNotEmpty()
    LazyVerticalGrid(
        columns = GridCells.Adaptive(cell),
        state = vm.gridState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(items, key = { it.path }) { e ->
            val sel = e.path in vm.selection
            val shape = RoundedCornerShape(10.dp)
            Column(
                Modifier.clip(shape).itemClick(vm, e),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.fillMaxWidth().aspectRatio(1f).clip(shape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .then(if (sel) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
                ) {
                    Thumb(vm, e, Modifier.fillMaxSize(), emoji)
                    if (selecting) {
                        Text(
                            if (sel) "✓" else "",
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
                                .size(22.dp)
                                .background(
                                    if (sel) MaterialTheme.colorScheme.primary else Color(0x55000000),
                                    CircleShape,
                                ),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                Text(
                    e.name, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 2.dp, vertical = 4.dp),
                )
            }
        }
    }
}
