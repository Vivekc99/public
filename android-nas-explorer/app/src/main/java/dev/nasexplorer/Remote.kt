package dev.nasexplorer

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

data class NasEntry(
    val name: String,
    /** Path relative to the share / start folder, '/' separated, no leading slash. */
    val path: String,
    val isDir: Boolean,
    val size: Long,
    val modified: Long,
    val created: Long = 0L,
)

fun joinPath(dir: String, name: String) = if (dir.isEmpty()) name else "$dir/$name"
fun parentPath(path: String) = path.substringBeforeLast('/', "")

/** An open file on the NAS, for reading (random access or streaming) or writing. */
interface RemoteFile : Closeable {
    val length: Long
    /** A fresh stream over the whole file, read from the start. */
    val inputStream: InputStream
    /** Stream to write the file's content into; close it to finish the upload. */
    val outputStream: OutputStream
    fun read(buffer: ByteArray, fileOffset: Long, offset: Int, len: Int): Int
}

/** For protocols that only offer "stream from byte N": re-opens the stream when a read jumps elsewhere. */
abstract class SeekableFile : RemoteFile {
    private var cur: InputStream? = null
    private var curPos = -1L

    protected abstract fun openAt(pos: Long): InputStream
    protected open fun onClose() {}

    override val inputStream: InputStream
        get() { closeCur(); return openAt(0) }

    override val outputStream: OutputStream
        get() = throw UnsupportedOperationException("read-only")

    override fun read(buffer: ByteArray, fileOffset: Long, offset: Int, len: Int): Int {
        if (cur == null || curPos != fileOffset) {
            closeCur()
            cur = openAt(fileOffset)
            curPos = fileOffset
        }
        val n = cur!!.read(buffer, offset, len)
        if (n > 0) curPos += n
        return n
    }

    private fun closeCur() {
        try { cur?.close() } catch (_: Exception) {}
        cur = null
        curPos = -1L
    }

    override fun close() {
        closeCur()
        onClose()
    }
}

/** One connection to one NAS location, whatever the protocol. */
abstract class Conn(val profile: Profile) : Closeable {
    abstract fun list(dir: String): List<NasEntry>
    abstract fun exists(path: String): Boolean
    abstract fun openRead(path: String): RemoteFile
    /** [size] is the number of bytes that will be written, or -1 if unknown. */
    abstract fun openWrite(path: String, size: Long = -1): RemoteFile
    abstract fun mkdir(path: String)
    abstract fun delete(path: String, isDir: Boolean)
    abstract fun rename(from: String, to: String)

    fun fileSize(f: RemoteFile): Long = f.length

    /** Total size in bytes of everything under a folder. */
    fun treeSize(dir: String): Long =
        list(dir).sumOf { if (it.isDir) treeSize(it.path) else it.size }

    /** Returns [name], or "name (1).ext", "name (2).ext"… so nothing is ever overwritten. */
    fun uniqueName(dir: String, name: String): String {
        if (!exists(joinPath(dir, name))) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (true) {
            val candidate = "$base ($i)$ext"
            if (!exists(joinPath(dir, candidate))) return candidate
            i++
        }
    }
}

fun makeConn(p: Profile): Conn = when (p.protocol) {
    Protocol.SMB -> SmbConn(p)
    Protocol.SFTP -> SftpConn(p)
    Protocol.WEBDAV -> DavConn(p)
}

class SessionPool {
    private val map = ConcurrentHashMap<String, Conn>()

    fun get(p: Profile): Conn = map.compute(p.id) { _, old ->
        if (old != null && old.profile == p) old else { old?.close(); makeConn(p) }
    }!!

    fun find(id: String): Conn? = map[id]
    fun drop(id: String) { map.remove(id)?.close() }
}
