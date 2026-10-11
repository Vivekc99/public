package dev.nasexplorer

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.protocol.transport.TransportException
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.msfscc.FileAttributes
import java.io.Closeable
import java.io.IOException
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import com.hierynomus.smbj.share.File as SmbFile

data class NasEntry(
    val name: String,
    /** Path relative to the share root, '/' separated, no leading slash. */
    val path: String,
    val isDir: Boolean,
    val size: Long,
    val modified: Long,
)

fun joinPath(dir: String, name: String) = if (dir.isEmpty()) name else "$dir/$name"
fun parentPath(path: String) = path.substringBeforeLast('/', "")
private fun sp(path: String) = path.trim('/').replace('/', '\\')

/** One connection to one share on one NAS. Reconnects automatically if the link drops. */
class SmbConn(val profile: Profile) : Closeable {
    private var client: SMBClient? = null
    private var connection: Connection? = null
    private var session: Session? = null
    private var diskShare: DiskShare? = null

    @Synchronized
    private fun share(): DiskShare {
        diskShare?.let { if (it.isConnected) return it }
        closeQuiet()
        val cfg = SmbConfig.builder()
            .withTimeout(30, TimeUnit.SECONDS)
            .withSoTimeout(60, TimeUnit.SECONDS)
            .build()
        val c = SMBClient(cfg)
        client = c
        val conn = c.connect(profile.host, profile.port)
        connection = conn
        val auth = if (profile.user.isBlank()) {
            AuthenticationContext.guest()
        } else {
            AuthenticationContext(profile.user, profile.password.toCharArray(), profile.domain)
        }
        val s = conn.authenticate(auth)
        session = s
        val sh = s.connectShare(profile.share) as DiskShare
        diskShare = sh
        return sh
    }

    @Synchronized
    private fun closeQuiet() {
        try { diskShare?.close() } catch (_: Exception) {}
        try { session?.close() } catch (_: Exception) {}
        try { connection?.close() } catch (_: Exception) {}
        try { client?.close() } catch (_: Exception) {}
        diskShare = null; session = null; connection = null; client = null
    }

    override fun close() = closeQuiet()

    private fun <T> op(block: (DiskShare) -> T): T {
        try {
            return block(share())
        } catch (e: Exception) {
            if (e is TransportException || (e is IOException && e !is java.io.FileNotFoundException)) {
                closeQuiet()
                return block(share())
            }
            throw e
        }
    }

    fun list(dir: String): List<NasEntry> = op { s ->
        s.list(sp(dir))
            .filter { it.fileName != "." && it.fileName != ".." }
            .map {
                val isDir = (it.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
                NasEntry(
                    name = it.fileName,
                    path = joinPath(dir, it.fileName),
                    isDir = isDir,
                    size = if (isDir) 0L else it.endOfFile,
                    modified = it.lastWriteTime.toEpochMillis(),
                )
            }
    }

    fun exists(path: String): Boolean = op { s -> s.fileExists(sp(path)) || s.folderExists(sp(path)) }

    fun openRead(path: String): SmbFile = op { s ->
        s.openFile(
            sp(path),
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null,
        )
    }

    fun openWrite(path: String): SmbFile = op { s ->
        s.openFile(
            sp(path),
            EnumSet.of(AccessMask.GENERIC_WRITE),
            EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OVERWRITE_IF,
            EnumSet.of(SMB2CreateOptions.FILE_SEQUENTIAL_ONLY),
        )
    }

    fun fileSize(f: SmbFile): Long = f.fileInformation.standardInformation.endOfFile

    fun mkdir(path: String) = op { s -> s.mkdir(sp(path)) }

    fun delete(path: String, isDir: Boolean) = op { s ->
        if (isDir) s.rmdir(sp(path), true) else s.rm(sp(path))
    }

    fun rename(from: String, to: String) = op { s ->
        s.open(
            sp(from),
            EnumSet.of(AccessMask.GENERIC_ALL),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null,
        ).use { it.rename(sp(to), false) }
    }

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

class SessionPool {
    private val map = ConcurrentHashMap<String, SmbConn>()

    fun get(p: Profile): SmbConn = map.compute(p.id) { _, old ->
        if (old != null && old.profile == p) old else { old?.close(); SmbConn(p) }
    }!!

    fun find(id: String): SmbConn? = map[id]
    fun drop(id: String) { map.remove(id)?.close() }
}
