package dev.nasexplorer

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface Overlay {
    data class Gallery(val items: List<NasEntry>, val start: Int) : Overlay
    data object Transfers : Overlay
    data class EditProfile(val profile: Profile?) : Overlay
}

data class Clip(val profile: Profile, val items: List<NasEntry>, val cut: Boolean)

enum class SortMode(val label: String) {
    Name("Name"), Date("Date (newest first)"), Size("Size (largest first)")
}

class AppViewModel(private val app: Application) : AndroidViewModel(app) {
    private val nas = app as NasApp

    var profiles by mutableStateOf(nas.store.load()); private set
    var current by mutableStateOf<Profile?>(null); private set
    var path by mutableStateOf(""); private set
    var entries by mutableStateOf<List<NasEntry>>(emptyList()); private set
    var loading by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var selection by mutableStateOf<Set<String>>(emptySet()); private set
    var clip by mutableStateOf<Clip?>(null); private set
    var busy by mutableStateOf<String?>(null); private set
    var sort by mutableStateOf(SortMode.Name)
    var showHidden by mutableStateOf(false)
    var overlay by mutableStateOf<Overlay?>(null)

    val transfers get() = nas.transfers
    val thumbs get() = nas.thumbs

    private val uiPrefs = app.getSharedPreferences("ui", android.content.Context.MODE_PRIVATE)
    var viewMode by mutableStateOf(
        runCatching { ViewMode.valueOf(uiPrefs.getString("view", "") ?: "") }.getOrDefault(ViewMode.Simple)
    ); private set

    fun setViewMode(m: ViewMode) {
        viewMode = m
        uiPrefs.edit().putString("view", m.name).apply()
    }
    val pool get() = nas.pool
    val intents = Channel<Intent>(Channel.BUFFERED)

    private var loadJob: Job? = null
    private var doneCount = 0

    init {
        // Refresh the folder listing whenever a transfer finishes.
        viewModelScope.launch {
            nas.transfers.tasks.map { list -> list.count { it.state == TState.Done } }.collect { n ->
                if (n > doneCount && current != null) load(path)
                doneCount = n
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(app, msg, Toast.LENGTH_SHORT).show()

    // ------------------------------------------------------------ profiles

    fun saveProfile(p: Profile) {
        profiles = if (profiles.any { it.id == p.id }) profiles.map { if (it.id == p.id) p else it } else profiles + p
        nas.store.save(profiles)
        overlay = null
    }

    fun deleteProfile(p: Profile) {
        profiles = profiles.filter { it.id != p.id }
        nas.store.save(profiles)
        nas.pool.drop(p.id)
        overlay = null
    }

    fun testProfile(p: Profile, onResult: (String) -> Unit) {
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { SmbConn(p).use { it.list("").size } }
            }
            onResult(r.fold({ "Connected – $it items in share root" }, { "Failed: ${it.friendly()}" }))
        }
    }

    fun openProfile(p: Profile) {
        current = p
        entries = emptyList()
        selection = emptySet()
        path = ""
        load("")
    }

    // ---------------------------------------------------------- browsing

    fun load(dir: String) {
        val p = current ?: return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            loading = true
            error = null
            try {
                val list = withContext(Dispatchers.IO) { nas.pool.get(p).list(dir) }
                path = dir
                entries = list
                selection = selection.filter { s -> list.any { it.path == s } }.toSet()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.friendly()
            } finally {
                loading = false
            }
        }
    }

    fun refresh() = load(path)

    fun visibleEntries(): List<NasEntry> {
        val base = if (showHidden) entries else entries.filterNot { isHidden(it.name) }
        val dirsFirst = compareBy<NasEntry> { !it.isDir }
        val cmp: Comparator<NasEntry> = when (sort) {
            SortMode.Name -> dirsFirst.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            SortMode.Date -> dirsFirst.thenByDescending { it.modified }
            SortMode.Size -> dirsFirst.thenByDescending { it.size }
        }
        return base.sortedWith(cmp)
    }

    fun canGoBack() = overlay != null || current != null

    fun back() {
        when {
            overlay != null -> overlay = null
            selection.isNotEmpty() -> selection = emptySet()
            current != null -> if (path.isEmpty()) {
                loadJob?.cancel()
                current = null
                entries = emptyList()
            } else load(parentPath(path))
        }
    }

    fun open(e: NasEntry) {
        if (e.isDir) { load(e.path); return }
        val files = visibleEntries().filter { !it.isDir }
        overlay = Overlay.Gallery(files, files.indexOf(e).coerceAtLeast(0))
    }

    fun saveOne(e: NasEntry) {
        val p = current ?: return
        transfers.download(p, listOf(e))
        toast("Saving ${e.name} to Downloads/NAS")
    }

    fun openExternal(e: NasEntry) {
        val p = current ?: return
        if (e.size > 1L shl 30) { toast("Too big to open directly – use Save to download it"); return }
        viewModelScope.launch {
            busy = "Opening ${e.name}…"
            try {
                val file = withContext(Dispatchers.IO) {
                    val dir = File(app.cacheDir, "open").apply { deleteRecursively(); mkdirs() }
                    val out = File(dir, e.name.replace('/', '_'))
                    nas.pool.get(p).openRead(e.path).use { f ->
                        f.inputStream.use { i -> out.outputStream().use { o -> i.copyTo(o) } }
                    }
                    out
                }
                val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", file)
                intents.send(
                    Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, mimeOf(e.name))
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (ex: Exception) {
                toast("Couldn't open: ${ex.friendly()}")
            } finally {
                busy = null
            }
        }
    }

    // --------------------------------------------------------- selection

    fun toggle(e: NasEntry) {
        selection = if (e.path in selection) selection - e.path else selection + e.path
    }

    fun selectAll() { selection = visibleEntries().map { it.path }.toSet() }
    fun clearSelection() { selection = emptySet() }
    fun selected(): List<NasEntry> = entries.filter { it.path in selection }

    // ------------------------------------------------------------ actions

    fun downloadSelected() {
        val p = current ?: return
        val items = selected()
        transfers.download(p, items)
        selection = emptySet()
        toast("Saving ${items.size} item(s) to Downloads/NAS")
    }

    fun upload(uris: List<Uri>) {
        val p = current ?: return
        if (uris.isEmpty()) return
        transfers.upload(p, path, uris)
        toast("Uploading ${uris.size} file(s)")
    }

    fun uploadTree(uri: Uri) {
        val p = current ?: return
        transfers.uploadTree(p, path, uri)
        toast("Uploading folder")
    }

    fun copySelection(cut: Boolean) {
        val p = current ?: return
        clip = Clip(p, selected(), cut)
        selection = emptySet()
    }

    fun cancelClip() { clip = null }

    fun paste() {
        val c = clip ?: return
        val p = current ?: return
        transfers.paste(c.profile, c.items, p, path, c.cut)
        clip = null
    }

    fun deleteSelected() {
        val p = current ?: return
        transfers.delete(p, selected())
        selection = emptySet()
    }

    fun mkdir(name: String) {
        val p = current ?: return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { nas.pool.get(p).mkdir(joinPath(path, name)) }
                load(path)
            } catch (e: Exception) {
                toast("Couldn't create folder: ${e.friendly()}")
            }
        }
    }

    fun rename(e: NasEntry, newName: String) {
        val p = current ?: return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { nas.pool.get(p).rename(e.path, joinPath(parentPath(e.path), newName)) }
                selection = emptySet()
                load(path)
            } catch (ex: Exception) {
                toast("Couldn't rename: ${ex.friendly()}")
            }
        }
    }
}
