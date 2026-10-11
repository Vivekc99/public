package dev.nasexplorer

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
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
import java.io.IOException
import java.util.EnumSet
import java.util.concurrent.TimeUnit
import com.hierynomus.smbj.share.File as SmbFile

private fun sp(path: String) = path.trim('/').replace('/', '\\')

private class SmbRemoteFile(private val f: SmbFile) : RemoteFile {
    override val length: Long by lazy { f.fileInformation.standardInformation.endOfFile }
    override val inputStream get() = f.inputStream
    override val outputStream get() = f.outputStream
    override fun read(buffer: ByteArray, fileOffset: Long, offset: Int, len: Int) =
        f.read(buffer, fileOffset, offset, len)
    override fun close() = f.close()
}

/** SMB 2/3 share (Synology, QNAP, Windows, Unraid, TrueNAS, routers…). Reconnects if the link drops. */
class SmbConn(profile: Profile) : Conn(profile) {
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

    override fun list(dir: String): List<NasEntry> = op { s ->
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
                    created = it.creationTime.toEpochMillis(),
                )
            }
    }

    override fun exists(path: String): Boolean = op { s -> s.fileExists(sp(path)) || s.folderExists(sp(path)) }

    override fun openRead(path: String): RemoteFile = SmbRemoteFile(op { s ->
        s.openFile(
            sp(path),
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null,
        )
    })

    override fun openWrite(path: String, size: Long): RemoteFile = SmbRemoteFile(op { s ->
        s.openFile(
            sp(path),
            EnumSet.of(AccessMask.GENERIC_WRITE),
            EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OVERWRITE_IF,
            EnumSet.of(SMB2CreateOptions.FILE_SEQUENTIAL_ONLY),
        )
    })

    override fun mkdir(path: String) { op { s -> s.mkdir(sp(path)) } }

    override fun delete(path: String, isDir: Boolean) {
        op { s -> if (isDir) s.rmdir(sp(path), true) else s.rm(sp(path)) }
    }

    override fun rename(from: String, to: String) {
        op { s ->
            s.open(
                sp(from),
                EnumSet.of(AccessMask.GENERIC_ALL),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null,
            ).use { it.rename(sp(to), false) }
        }
    }
}
