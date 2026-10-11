package dev.nasexplorer

import android.util.Xml
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.Pipe
import okio.buffer
import org.xmlpull.v1.XmlPullParser
import java.io.FileNotFoundException
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.time.OffsetDateTime
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

private val PROPFIND_BODY =
    """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/><d:creationdate/></d:prop></d:propfind>"""

private class RawEntry(var href: String = "", var isDir: Boolean = false, var size: Long = 0, var modified: Long = 0, var created: Long = 0)

/** WebDAV server (Nextcloud, Synology WebDAV Server, QNAP, nginx/Apache dav…). "share" is the path on the server. */
class DavConn(profile: Profile) : Conn(profile) {
    private val client: OkHttpClient = buildClient()
    private val authHeader: String? =
        if (profile.user.isNotBlank()) Credentials.basic(profile.user, profile.password, Charsets.UTF_8) else null
    private val scheme = if (profile.https) "https" else "http"
    private val baseTrim = profile.share.trim().trim('/')

    private fun buildClient(): OkHttpClient {
        val b = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
        if (profile.https && profile.trustSelfSigned) {
            // Self-signed certificate: pin the one the server shows the first time (trust on first use).
            val tm = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                    val fp = MessageDigest.getInstance("SHA-256").digest(chain[0].encoded)
                        .joinToString("") { "%02x".format(it) }
                    HostKeys.verify("tls:${profile.host}:${profile.port}", fp)
                }
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf(tm), SecureRandom())
            b.sslSocketFactory(ctx.socketFactory, tm).hostnameVerifier { _, _ -> true }
        }
        return b.build()
    }

    private fun url(path: String, dir: Boolean = false): HttpUrl {
        val b = HttpUrl.Builder().scheme(scheme).host(profile.host).port(profile.port)
        (baseTrim + "/" + path).split('/').filter { it.isNotEmpty() }.forEach { b.addPathSegment(it) }
        if (dir) b.addPathSegment("")
        return b.build()
    }

    private fun call(rb: Request.Builder): Response {
        authHeader?.let { rb.header("Authorization", it) }
        return client.newCall(rb.build()).execute()
    }

    private fun check(r: Response, vararg ok: Int) {
        if (r.code in ok) return
        val code = r.code
        r.close()
        when (code) {
            401, 403 -> throw IOException("Access denied (HTTP $code) – check username/password")
            404 -> throw FileNotFoundException("Not found (HTTP 404) – check the path")
            else -> throw IOException("Server replied HTTP $code")
        }
    }

    private fun propfind(path: String, depth: Int, dir: Boolean): List<RawEntry> {
        val rb = Request.Builder().url(url(path, dir))
            .method("PROPFIND", PROPFIND_BODY.toRequestBody("application/xml".toMediaType()))
            .header("Depth", depth.toString())
        call(rb).use { r ->
            check(r, 207, 200)
            return parse(r.body!!.byteStream())
        }
    }

    private fun parse(ins: InputStream): List<RawEntry> {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        p.setInput(ins, null)
        val out = ArrayList<RawEntry>()
        var cur: RawEntry? = null
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                when (p.name) {
                    "response" -> cur = RawEntry()
                    "href" -> if (cur != null && cur.href.isEmpty()) cur.href = p.nextText().trim()
                    "collection" -> cur?.isDir = true
                    "getcontentlength" -> cur?.size = p.nextText().trim().toLongOrNull() ?: 0L
                    "getlastmodified" -> cur?.modified = parseHttpDate(p.nextText().trim())
                    "creationdate" -> cur?.created = parseIso(p.nextText().trim())
                }
            } else if (ev == XmlPullParser.END_TAG && p.name == "response") {
                cur?.let { out.add(it) }
                cur = null
            }
            ev = p.next()
        }
        return out
    }

    private fun parseHttpDate(s: String): Long = try {
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).parse(s)?.time ?: 0L
    } catch (_: Exception) { 0L }

    private fun parseIso(s: String): Long = try {
        OffsetDateTime.parse(s).toInstant().toEpochMilli()
    } catch (_: Exception) { 0L }

    /** Server path (from a response's href) turned into a path relative to our start folder. */
    private fun relative(href: String): String {
        val noHost = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://[^/]*").replace(href, "")
        val full = android.net.Uri.decode(noHost).trim('/')
        return if (baseTrim.isNotEmpty() && full.startsWith(baseTrim)) full.substring(baseTrim.length).trim('/') else full
    }

    override fun list(dir: String): List<NasEntry> {
        val self = dir.trim('/')
        return propfind(dir, 1, true).mapNotNull { r ->
            val rel = relative(r.href)
            if (rel == self) return@mapNotNull null
            val name = rel.substringAfterLast('/')
            if (name.isEmpty()) return@mapNotNull null
            NasEntry(name, joinPath(self, name), r.isDir, if (r.isDir) 0L else r.size, r.modified,
                if (r.created > 0) r.created else r.modified)
        }
    }

    override fun exists(path: String): Boolean {
        val rb = Request.Builder().url(url(path))
            .method("PROPFIND", PROPFIND_BODY.toRequestBody("application/xml".toMediaType()))
            .header("Depth", "0")
        call(rb).use { r ->
            if (r.code == 404) return false
            check(r, 207, 200)
            return true
        }
    }

    private fun sizeOf(path: String): Long {
        val e = propfind(path, 0, false).firstOrNull()
        if (e != null && e.size > 0) return e.size
        call(Request.Builder().url(url(path)).head()).use { r ->
            check(r, 200)
            return r.header("Content-Length")?.toLongOrNull() ?: 0L
        }
    }

    override fun openRead(path: String): RemoteFile = DavReadFile(path, sizeOf(path))

    private inner class DavReadFile(private val path: String, override val length: Long) : SeekableFile() {
        override fun openAt(pos: Long): InputStream {
            val rb = Request.Builder().url(url(path)).get()
            if (pos > 0) rb.header("Range", "bytes=$pos-")
            val r = call(rb)
            check(r, 200, 206)
            val body = r.body!!.byteStream()
            if (pos > 0 && r.code == 200) {
                // Server ignored the Range request: skip ahead ourselves.
                var left = pos
                while (left > 0) {
                    val k = body.skip(left)
                    if (k <= 0) break
                    left -= k
                }
            }
            return object : FilterInputStream(body) {
                override fun close() { super.close(); r.close() }
            }
        }
    }

    override fun openWrite(path: String, size: Long): RemoteFile = DavWriteFile(path, size)

    private inner class DavWriteFile(path: String, size: Long) : RemoteFile {
        private val pipe = Pipe(1L shl 20)
        private val sink: BufferedSink = pipe.sink.buffer()
        @Volatile private var error: Throwable? = null
        private var closed = false

        private val thread = Thread {
            try {
                val body = object : RequestBody() {
                    override fun contentType() = "application/octet-stream".toMediaType()
                    override fun contentLength() = size
                    override fun isOneShot() = true
                    override fun writeTo(sink: BufferedSink) { sink.writeAll(pipe.source) }
                }
                call(Request.Builder().url(url(path)).put(body)).use { r -> check(r, 200, 201, 204) }
            } catch (t: Throwable) {
                error = t
                pipe.cancel()
            }
        }.apply { isDaemon = true; start() }

        override val length: Long get() = 0L
        override val inputStream: InputStream get() = throw UnsupportedOperationException("write-only")
        override val outputStream: OutputStream = object : OutputStream() {
            override fun write(b: Int) { sink.writeByte(b) }
            override fun write(b: ByteArray, off: Int, len: Int) {
                try { sink.write(b, off, len) } catch (e: IOException) { throw error ?: e }
            }
            override fun flush() { sink.flush() }
            override fun close() { finish() }
        }

        override fun read(buffer: ByteArray, fileOffset: Long, offset: Int, len: Int): Int =
            throw UnsupportedOperationException("write-only")

        private fun finish() {
            if (closed) return
            closed = true
            try { sink.close() } catch (_: IOException) {}
            thread.join()
            error?.let { throw if (it is IOException) it else IOException(it.message, it) }
        }

        override fun close() = finish()
    }

    override fun mkdir(path: String) {
        call(Request.Builder().url(url(path, true)).method("MKCOL", null)).use { check(it, 201, 200) }
    }

    override fun delete(path: String, isDir: Boolean) {
        call(Request.Builder().url(url(path, isDir)).delete()).use { check(it, 200, 202, 204) }
    }

    override fun rename(from: String, to: String) {
        val rb = Request.Builder().url(url(from))
            .method("MOVE", null)
            .header("Destination", url(to).toString())
            .header("Overwrite", "F")
        call(rb).use { check(it, 201, 204, 200) }
    }

    override fun close() {
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }
}
