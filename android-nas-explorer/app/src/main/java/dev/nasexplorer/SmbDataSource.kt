package dev.nasexplorer

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import java.io.IOException

fun smbUri(profileId: String, path: String): Uri =
    Uri.Builder().scheme("smb").authority(profileId).path("/$path").build()

/**
 * Lets the video player stream (and seek in) a file straight off the NAS.
 * Reads ahead in 1 MB blocks so playback isn't limited by SMB round-trip time.
 */
class SmbDataSource(private val pool: SessionPool) : BaseDataSource(true) {
    private var file: RemoteFile? = null
    private var uri: Uri? = null
    private var pos = 0L
    private var remaining = 0L
    private var opened = false
    private val buf = ByteArray(1 shl 20)
    private var bufPos = 0
    private var bufLen = 0

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val id = dataSpec.uri.authority ?: throw IOException("Bad NAS uri")
        val conn = pool.find(id) ?: throw IOException("Not connected to NAS")
        val f = conn.openRead(dataSpec.uri.path.orEmpty().trimStart('/'))
        file = f
        val size = conn.fileSize(f)
        if (dataSpec.position > size) {
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }
        pos = dataSpec.position
        remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else size - pos
        bufPos = 0; bufLen = 0
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        if (bufPos >= bufLen) {
            val want = minOf(buf.size.toLong(), remaining).toInt()
            val n = file!!.read(buf, pos, 0, want)
            if (n <= 0) return C.RESULT_END_OF_INPUT
            pos += n
            bufPos = 0
            bufLen = n
        }
        val n = minOf(length.toLong(), (bufLen - bufPos).toLong(), remaining).toInt()
        System.arraycopy(buf, bufPos, buffer, offset, n)
        bufPos += n
        remaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        try { file?.close() } catch (_: Exception) {}
        file = null
        uri = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    class Factory(private val pool: SessionPool) : DataSource.Factory {
        override fun createDataSource(): DataSource = SmbDataSource(pool)
    }
}
