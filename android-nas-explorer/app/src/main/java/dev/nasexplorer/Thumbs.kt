package dev.nasexplorer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.util.LruCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import com.hierynomus.smbj.share.File as SmbFile

private const val THUMB_PX = 384

fun rotateByExif(bmp: Bitmap, orientation: Int): Bitmap {
    val m = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
        else -> return bmp
    }
    return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
}

/** Makes small preview pictures for photos and videos on the NAS, cached in memory and on disk. */
class ThumbLoader(ctx: Context, private val pool: SessionPool) {
    private val mem = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val dir = File(ctx.cacheDir, "thumbs").apply { mkdirs() }
    private val imageSlots = Semaphore(4)
    private val videoSlots = Semaphore(2)
    private val failed = ConcurrentHashMap.newKeySet<String>()

    private fun hash(s: String) =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    suspend fun load(p: Profile, e: NasEntry): Bitmap? {
        val key = hash("${p.id}|${e.path}|${e.modified}|${e.size}")
        mem.get(key)?.let { return it }
        if (key in failed) return null
        return withContext(Dispatchers.IO) {
            val cached = File(dir, "$key.jpg")
            if (cached.exists()) {
                BitmapFactory.decodeFile(cached.path)?.let { mem.put(key, it); return@withContext it }
            }
            val isVideo = kindOf(e.name) == Kind.Video
            val bmp = (if (isVideo) videoSlots else imageSlots).withPermit {
                try {
                    if (isVideo) videoThumb(pool.get(p), e) else imageThumb(pool.get(p), e)
                } catch (ex: CancellationException) {
                    throw ex
                } catch (t: Throwable) {
                    null
                }
            }
            if (bmp == null) {
                failed.add(key)
                null
            } else {
                mem.put(key, bmp)
                try { cached.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) } } catch (_: Exception) {}
                bmp
            }
        }
    }

    private fun imageThumb(conn: SmbConn, e: NasEntry): Bitmap? {
        conn.openRead(e.path).use { f ->
            if (e.size <= 20L shl 20) {
                val bytes = f.inputStream.use { it.readBytes() }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= THUMB_PX) sample *= 2
                val bmp = BitmapFactory.decodeByteArray(
                    bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }
                ) ?: return null
                val o = try {
                    ExifInterface(ByteArrayInputStream(bytes))
                        .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                } catch (_: Exception) {
                    ExifInterface.ORIENTATION_NORMAL
                }
                return rotateByExif(bmp, o)
            }
            // Big photo: just use the small preview embedded in the first bytes of the file.
            val head = ByteArray(131072)
            var got = 0
            while (got < head.size) {
                val n = f.read(head, got.toLong(), got, head.size - got)
                if (n <= 0) break
                got += n
            }
            val exif = ExifInterface(ByteArrayInputStream(head, 0, got))
            if (!exif.hasThumbnail()) return null
            val t = exif.thumbnailBitmap ?: return null
            return rotateByExif(t, exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL))
        }
    }

    private fun videoThumb(conn: SmbConn, e: NasEntry): Bitmap? {
        val file = conn.openRead(e.path)
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(SmbMediaDataSource(file, conn.fileSize(file)))
            return r.getScaledFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, THUMB_PX, THUMB_PX)
        } finally {
            try { r.release() } catch (_: Exception) {}
            try { file.close() } catch (_: Exception) {}
        }
    }
}

/** Random-access reads for MediaMetadataRetriever, with a small read-ahead block to avoid many tiny SMB requests. */
private class SmbMediaDataSource(private val f: SmbFile, private val total: Long) : MediaDataSource() {
    private val block = ByteArray(512 * 1024)
    private var blockStart = -1L
    private var blockLen = 0

    override fun getSize() = total

    @Synchronized
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= total) return -1
        if (blockStart < 0 || position < blockStart || position >= blockStart + blockLen) {
            val want = minOf(block.size.toLong(), total - position).toInt()
            val n = f.read(block, position, 0, want)
            if (n <= 0) return -1
            blockStart = position
            blockLen = n
        }
        val from = (position - blockStart).toInt()
        val n = minOf(size, blockLen - from)
        System.arraycopy(block, from, buffer, offset, n)
        return n
    }

    override fun close() {}
}
