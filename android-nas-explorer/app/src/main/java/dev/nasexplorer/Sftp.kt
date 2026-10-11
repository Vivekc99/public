package dev.nasexplorer

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpException
import com.jcraft.jsch.SftpProgressMonitor
import com.jcraft.jsch.UserInfo
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.Semaphore

private fun sha256Hex(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

/** SFTP (SSH) server. The profile's "share" field is the start folder; blank = the login home folder. */
class SftpConn(profile: Profile) : Conn(profile) {
    private var session: Session? = null
    private var control: ChannelSftp? = null
    private val controlLock = Any()
    // OpenSSH allows ~10 channels per connection; keep a few spare.
    private val slots = Semaphore(7)

    private fun abs(path: String): String {
        val b = profile.share.trim()
        return when {
            b.isEmpty() -> path.ifEmpty { "." }
            path.isEmpty() -> b
            else -> b.trimEnd('/') + "/" + path
        }
    }

    @Synchronized
    private fun sess(): Session {
        session?.let { if (it.isConnected) return it }
        closeQuiet()
        val j = JSch()
        j.hostKeyRepository = object : HostKeyRepository {
            // Trust on first use: remember the server's key, refuse if it ever changes (before sending any login).
            override fun check(host: String, key: ByteArray): Int {
                HostKeys.verify("ssh:$host", sha256Hex(key))
                return HostKeyRepository.OK
            }
            override fun add(hostkey: HostKey?, ui: UserInfo?) {}
            override fun remove(host: String?, type: String?) {}
            override fun remove(host: String?, type: String?, key: ByteArray?) {}
            override fun getKnownHostsRepositoryID(): String = "nas-explorer"
            override fun getHostKey(): Array<HostKey>? = null
            override fun getHostKey(host: String?, type: String?): Array<HostKey>? = null
        }
        val useKey = profile.privateKey.isNotBlank()
        if (useKey) {
            val pass = profile.password.toByteArray().takeIf { it.isNotEmpty() }
            j.addIdentity("nas-key", profile.privateKey.trim().toByteArray(), null, pass)
        }
        val s = j.getSession(profile.user, profile.host, profile.port)
        if (!useKey) s.setPassword(profile.password)
        s.setConfig("StrictHostKeyChecking", "no")
        s.setConfig("PreferredAuthentications", if (useKey) "publickey" else "password,keyboard-interactive")
        s.setServerAliveInterval(15000)
        s.connect(15000)
        session = s
        return s
    }

    @Synchronized
    private fun closeQuiet() {
        try { control?.disconnect() } catch (_: Exception) {}
        try { session?.disconnect() } catch (_: Exception) {}
        control = null
        session = null
    }

    override fun close() = closeQuiet()

    private fun newChannel(): ChannelSftp {
        val c = sess().openChannel("sftp") as ChannelSftp
        c.connect(15000)
        return c
    }

    private fun isConnectionProblem(e: Exception) =
        e is JSchException || e is IOException || (e is SftpException && (e.id == 6 || e.id == 7))

    private fun <T> ctl(block: (ChannelSftp) -> T): T = synchronized(controlLock) {
        fun channel(): ChannelSftp {
            control?.let { if (it.isConnected) return it }
            return newChannel().also { control = it }
        }
        try {
            block(channel())
        } catch (e: Exception) {
            if (!isConnectionProblem(e)) throw e
            closeQuiet()
            block(channel())
        }
    }

    override fun list(dir: String): List<NasEntry> = ctl { c ->
        val out = ArrayList<NasEntry>()
        for (entry in c.ls(abs(dir))) {
            val o = entry as ChannelSftp.LsEntry
            val n = o.filename
            if (n == "." || n == "..") continue
            var a = o.attrs
            var isDir = a.isDir
            if (a.isLink) {
                try { a = c.stat(abs(joinPath(dir, n))); isDir = a.isDir } catch (_: Exception) {}
            }
            val m = a.mTime * 1000L
            out.add(NasEntry(n, joinPath(dir, n), isDir, if (isDir) 0L else a.size, m, m))
        }
        out
    }

    override fun exists(path: String): Boolean = ctl { c ->
        try {
            c.stat(abs(path)); true
        } catch (e: SftpException) {
            if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) false else throw e
        }
    }

    override fun mkdir(path: String) { ctl { c -> c.mkdir(abs(path)) } }

    override fun delete(path: String, isDir: Boolean) {
        ctl { c -> if (isDir) rmTree(c, abs(path)) else c.rm(abs(path)) }
    }

    private fun rmTree(c: ChannelSftp, p: String) {
        for (entry in c.ls(p)) {
            val o = entry as ChannelSftp.LsEntry
            if (o.filename == "." || o.filename == "..") continue
            val child = "$p/${o.filename}"
            if (o.attrs.isDir) rmTree(c, child) else c.rm(child)
        }
        c.rmdir(p)
    }

    override fun rename(from: String, to: String) { ctl { c -> c.rename(abs(from), abs(to)) } }

    override fun openRead(path: String): RemoteFile {
        val p = abs(path)
        slots.acquire()
        try {
            val c = newChannel()
            val size = try { c.stat(p).size } catch (t: Throwable) { c.disconnect(); throw t }
            return SftpReadFile(c, p, size) { slots.release() }
        } catch (t: Throwable) {
            slots.release()
            throw t
        }
    }

    override fun openWrite(path: String, size: Long): RemoteFile {
        val p = abs(path)
        slots.acquire()
        try {
            val c = newChannel()
            return SftpWriteFile(c, p) { slots.release() }
        } catch (t: Throwable) {
            slots.release()
            throw t
        }
    }
}

private class SftpReadFile(
    private val c: ChannelSftp,
    private val p: String,
    override val length: Long,
    private val release: () -> Unit,
) : SeekableFile() {
    override fun openAt(pos: Long): InputStream = c.get(p, null as SftpProgressMonitor?, pos)

    override fun onClose() {
        try { c.disconnect() } catch (_: Exception) {}
        release()
    }
}

private class SftpWriteFile(
    private val c: ChannelSftp,
    private val p: String,
    private val release: () -> Unit,
) : RemoteFile {
    private var out: OutputStream? = null
    override val length: Long get() = 0L
    override val inputStream: InputStream get() = throw UnsupportedOperationException("write-only")
    override val outputStream: OutputStream
        get() = out ?: c.put(p, ChannelSftp.OVERWRITE).also { out = it }

    override fun read(buffer: ByteArray, fileOffset: Long, offset: Int, len: Int): Int =
        throw UnsupportedOperationException("write-only")

    override fun close() {
        try { out?.close() } catch (_: Exception) {}
        try { c.disconnect() } catch (_: Exception) {}
        release()
    }
}
