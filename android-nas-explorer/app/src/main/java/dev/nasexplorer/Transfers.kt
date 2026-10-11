package dev.nasexplorer

import android.app.Application
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

enum class TState { Queued, Running, Done, Failed, Cancelled }

data class TransferTask(
    val id: Long,
    val kind: String,
    val title: String,
    val state: TState = TState.Queued,
    val done: Long = 0,
    val total: Long = 0,
    val error: String? = null,
) {
    val active get() = state == TState.Queued || state == TState.Running
}

class Progress(val done: AtomicLong = AtomicLong(), val total: AtomicLong = AtomicLong())

/**
 * Runs downloads, uploads, copies, moves and deletes one after another in the background.
 * Downloads land in the phone's Downloads/NAS folder.
 */
class TransferManager(private val app: Application, private val pool: SessionPool) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _tasks = MutableStateFlow<List<TransferTask>>(emptyList())
    val tasks: StateFlow<List<TransferTask>> = _tasks.asStateFlow()

    private val works = ConcurrentHashMap<Long, suspend (Progress) -> Unit>()
    private val jobs = ConcurrentHashMap<Long, Job>()
    private val queue = Channel<Long>(Channel.UNLIMITED)
    private var nextId = 0L

    init {
        scope.launch { for (id in queue) runTask(id) }
    }

    private fun update(id: Long, f: (TransferTask) -> TransferTask) =
        _tasks.update { list -> list.map { if (it.id == id) f(it) else it } }

    private fun enqueue(kind: String, title: String, work: suspend (Progress) -> Unit) {
        val id = synchronized(this) { ++nextId }
        works[id] = work
        _tasks.update { it + TransferTask(id, kind, title) }
        queue.trySend(id)
        TransferService.start(app)
    }

    private suspend fun runTask(id: Long) {
        val work = works[id] ?: return
        if (_tasks.value.firstOrNull { it.id == id }?.state != TState.Queued) return
        update(id) { it.copy(state = TState.Running) }
        val pr = Progress()
        val job = scope.launch {
            val ticker = launch {
                while (true) {
                    update(id) { it.copy(done = pr.done.get(), total = pr.total.get()) }
                    delay(300)
                }
            }
            try {
                work(pr)
                ticker.cancel()
                update(id) {
                    it.copy(
                        state = TState.Done,
                        done = pr.done.get(),
                        total = maxOf(pr.total.get(), pr.done.get()),
                    )
                }
            } catch (e: CancellationException) {
                ticker.cancel()
                update(id) { it.copy(state = TState.Cancelled) }
                throw e
            } catch (e: Throwable) {
                ticker.cancel()
                update(id) { it.copy(state = TState.Failed, error = e.friendly()) }
            }
        }
        jobs[id] = job
        job.join()
        jobs.remove(id)
        works.remove(id)
    }

    fun cancel(id: Long) {
        val t = _tasks.value.firstOrNull { it.id == id } ?: return
        when (t.state) {
            TState.Queued -> update(id) { it.copy(state = TState.Cancelled) }
            TState.Running -> jobs[id]?.cancel()
            else -> {}
        }
    }

    fun clearFinished() = _tasks.update { list -> list.filter { it.active } }

    // ---------------------------------------------------------------- download

    fun download(p: Profile, items: List<NasEntry>) {
        for (e in items) {
            enqueue("Download", e.name) { pr ->
                val conn = pool.get(p)
                if (e.isDir) {
                    pr.total.addAndGet(conn.treeSize(e.path))
                    downloadDir(conn, e.path, "Download/NAS/${e.name}", pr)
                } else {
                    pr.total.addAndGet(e.size)
                    downloadFile(conn, e.path, e.name, "Download/NAS", pr)
                }
            }
        }
    }

    private suspend fun downloadDir(conn: Conn, remote: String, relDir: String, pr: Progress) {
        for (c in conn.list(remote)) {
            currentCoroutineContext().ensureActive()
            if (c.isDir) downloadDir(conn, c.path, "$relDir/${c.name}", pr)
            else downloadFile(conn, c.path, c.name, relDir, pr)
        }
    }

    private suspend fun downloadFile(conn: Conn, remote: String, name: String, relDir: String, pr: Progress) {
        val cr = app.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mimeOf(name))
            put(MediaStore.Downloads.RELATIVE_PATH, relDir)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Cannot create $name in Downloads")
        try {
            conn.openRead(remote).use { f ->
                f.inputStream.use { ins ->
                    (cr.openOutputStream(uri) ?: throw IllegalStateException("Cannot write $name")).use { out ->
                        pump(ins, out, pr.done)
                    }
                }
            }
            cr.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (t: Throwable) {
            cr.delete(uri, null, null)
            throw t
        }
    }

    // ------------------------------------------------------------------ upload

    fun upload(p: Profile, remoteDir: String, uris: List<Uri>) {
        for (u in uris) {
            val name = displayName(u)
            enqueue("Upload", name) { pr ->
                val conn = pool.get(p)
                val sz = sizeOf(u)
                pr.total.addAndGet(sz)
                uploadStream(conn, remoteDir, name, sz, pr) {
                    app.contentResolver.openInputStream(u) ?: throw IllegalStateException("Cannot read $name")
                }
            }
        }
    }

    fun uploadTree(p: Profile, remoteDir: String, tree: Uri) {
        val root = DocumentFile.fromTreeUri(app, tree) ?: return
        val name = root.name ?: "folder"
        enqueue("Upload", "$name/") { pr ->
            val conn = pool.get(p)
            pr.total.addAndGet(docTreeSize(root))
            val target = joinPath(remoteDir, conn.uniqueName(remoteDir, name))
            conn.mkdir(target)
            uploadDoc(conn, root, target, pr)
        }
    }

    private fun docTreeSize(d: DocumentFile): Long =
        d.listFiles().sumOf { if (it.isDirectory) docTreeSize(it) else it.length() }

    private suspend fun uploadDoc(conn: Conn, dir: DocumentFile, remoteDir: String, pr: Progress) {
        for (c in dir.listFiles()) {
            currentCoroutineContext().ensureActive()
            val n = c.name ?: continue
            if (c.isDirectory) {
                val sub = joinPath(remoteDir, n)
                if (!conn.exists(sub)) conn.mkdir(sub)
                uploadDoc(conn, c, sub, pr)
            } else {
                uploadStream(conn, remoteDir, n, c.length(), pr) {
                    app.contentResolver.openInputStream(c.uri) ?: throw IllegalStateException("Cannot read $n")
                }
            }
        }
    }

    private suspend fun uploadStream(
        conn: Conn, remoteDir: String, name: String, size: Long, pr: Progress, open: () -> InputStream,
    ) {
        val target = joinPath(remoteDir, conn.uniqueName(remoteDir, name))
        try {
            conn.openWrite(target, size).use { f ->
                f.outputStream.use { out -> open().use { ins -> pump(ins, out, pr.done) } }
            }
        } catch (t: Throwable) {
            try { conn.delete(target, false) } catch (_: Exception) {}
            throw t
        }
    }

    private fun displayName(u: Uri): String =
        app.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: (u.lastPathSegment ?: "file")

    private fun sizeOf(u: Uri): Long =
        app.contentResolver.query(u, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else 0L
        } ?: 0L

    // ------------------------------------------------------- copy / move / delete

    /** Copy or move [items] from [src] into [dstDir] on [dst]. Works between two different NAS profiles too. */
    fun paste(src: Profile, items: List<NasEntry>, dst: Profile, dstDir: String, move: Boolean) {
        for (e in items) {
            enqueue(if (move) "Move" else "Copy", e.name) { pr ->
                val sc = pool.get(src)
                val dc = pool.get(dst)
                val sameShare = src.id == dst.id
                if (e.isDir && sameShare && (dstDir == e.path || dstDir.startsWith(e.path + "/"))) {
                    throw IllegalArgumentException("Can't put a folder inside itself")
                }
                if (move && sameShare) {
                    if (parentPath(e.path) == dstDir) return@enqueue
                    dc.rename(e.path, joinPath(dstDir, dc.uniqueName(dstDir, e.name)))
                } else {
                    pr.total.addAndGet(if (e.isDir) sc.treeSize(e.path) else e.size)
                    copyEntry(sc, e, dc, dstDir, pr)
                    if (move) sc.delete(e.path, e.isDir)
                }
            }
        }
    }

    private suspend fun copyEntry(src: Conn, e: NasEntry, dst: Conn, dstDir: String, pr: Progress) {
        currentCoroutineContext().ensureActive()
        val newPath = joinPath(dstDir, dst.uniqueName(dstDir, e.name))
        if (e.isDir) {
            dst.mkdir(newPath)
            for (c in src.list(e.path)) copyEntry(src, c, dst, newPath, pr)
        } else {
            try {
                src.openRead(e.path).use { rf ->
                    rf.inputStream.use { ins ->
                        dst.openWrite(newPath, e.size).use { wf -> wf.outputStream.use { out -> pump(ins, out, pr.done) } }
                    }
                }
            } catch (t: Throwable) {
                try { dst.delete(newPath, false) } catch (_: Exception) {}
                throw t
            }
        }
    }

    fun delete(p: Profile, items: List<NasEntry>) {
        enqueue("Delete", if (items.size == 1) items[0].name else "${items.size} items") { _ ->
            val conn = pool.get(p)
            for (e in items) {
                currentCoroutineContext().ensureActive()
                conn.delete(e.path, e.isDir)
            }
        }
    }

    private suspend fun pump(ins: InputStream, out: OutputStream, counter: AtomicLong) {
        val buf = ByteArray(1 shl 20)
        while (true) {
            currentCoroutineContext().ensureActive()
            val n = ins.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            counter.addAndGet(n.toLong())
        }
    }
}
