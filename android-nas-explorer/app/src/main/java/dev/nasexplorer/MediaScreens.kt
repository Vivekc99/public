package dev.nasexplorer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import androidx.activity.ComponentActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

/** Swipe left/right through every file in the folder: photos, videos, and a file-type card for the rest. */
@Composable
fun GalleryScreen(vm: AppViewModel, o: Overlay.Gallery) {
    val ctx = LocalContext.current
    val profile = vm.current ?: return
    val items = o.items
    val pager = rememberPagerState(initialPage = o.start) { items.size }
    val scope = rememberCoroutineScope()
    val player = remember {
        ExoPlayer.Builder(ctx)
            .setMediaSourceFactory(DefaultMediaSourceFactory(SmbDataSource.Factory(vm.pool)))
            .build()
    }

    DisposableEffect(Unit) {
        val window = (ctx as ComponentActivity).window
        val ctl = WindowInsetsControllerCompat(window, window.decorView)
        ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ctl.hide(WindowInsetsCompat.Type.systemBars())
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                // When a video/song finishes, move on to the next file.
                if (state == Player.STATE_ENDED && pager.currentPage + 1 < items.size) {
                    scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            ctl.show(WindowInsetsCompat.Type.systemBars())
            player.release()
        }
    }

    val settled = pager.settledPage
    LaunchedEffect(settled) {
        val e = items[settled]
        val k = kindOf(e.name)
        if (k == Kind.Video || k == Kind.Audio) {
            player.setMediaItem(
                MediaItem.Builder()
                    .setUri(smbUri(profile.id, e.path))
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(e.name).build())
                    .build()
            )
            player.prepare()
            player.playWhenReady = true
        } else {
            player.stop()
            player.clearMediaItems()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pager,
            beyondViewportPageCount = 1,
            key = { items[it].path },
            modifier = Modifier.fillMaxSize(),
        ) { i ->
            val e = items[i]
            when (kindOf(e.name)) {
                Kind.Image -> ImagePage(vm, profile, e)
                Kind.Video, Kind.Audio -> MediaPage(player, e, active = i == pager.settledPage)
                Kind.Other -> FilePage(vm, e)
            }
        }
        Row(
            Modifier.align(Alignment.TopStart).fillMaxWidth()
                .background(Color(0x66000000))
                .windowInsetsPadding(WindowInsets.safeDrawing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { vm.back() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
            }
            Text(
                "${pager.currentPage + 1}/${items.size}  ${items[pager.currentPage].name}",
                color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = 16.dp),
            )
        }
    }
}

@Composable
private fun MediaPage(player: ExoPlayer, e: NasEntry, active: Boolean) {
    if (active) {
        AndroidView(
            modifier = Modifier.fillMaxSize().background(Color.Black),
            factory = { c ->
                PlayerView(c).apply {
                    this.player = player
                    keepScreenOn = true
                }
            },
        )
    } else {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Text(iconFor(e), fontSize = 64.sp, modifier = Modifier.align(Alignment.Center))
        }
    }
}

@Composable
private fun FilePage(vm: AppViewModel, e: NasEntry) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(bigIconFor(e.name), fontSize = 110.sp)
        Spacer(Modifier.height(8.dp))
        val ext = e.name.substringAfterLast('.', "").uppercase()
        if (ext.isNotEmpty()) Text("$ext file", color = Color.LightGray)
        Spacer(Modifier.height(16.dp))
        Text(e.name, color = Color.White, textAlign = TextAlign.Center)
        Text("${humanSize(e.size)} · ${humanDate(e.modified)}", color = Color.Gray)
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
            Button(onClick = { vm.openExternal(e) }) { Text("Open with…") }
            OutlinedButton(onClick = { vm.saveOne(e) }) { Text("⬇ Save") }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ImagePage(vm: AppViewModel, profile: Profile, entry: NasEntry) {
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    // One finger only pans when zoomed in, so at normal size a swipe goes to the next page.
    val tState = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 8f)
        offset = if (scale == 1f) Offset.Zero else offset + pan
    }

    LaunchedEffect(entry.path) {
        try {
            bitmap = withContext(Dispatchers.IO) {
                val bytes = vm.pool.get(profile).openRead(entry.path).use { f -> f.inputStream.use { it.readBytes() } }
                decode(bytes)
            }
        } catch (e: Exception) {
            error = e.friendly()
        }
    }

    Box(Modifier.fillMaxSize()) {
        val bmp = bitmap
        when {
            bmp != null -> Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = entry.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = {
                            if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 3f
                        })
                    }
                    .transformable(state = tState, canPan = { scale > 1f })
                    .graphicsLayer(
                        scaleX = scale, scaleY = scale,
                        translationX = offset.x, translationY = offset.y,
                    ),
            )
            error != null -> Text(
                "Couldn't load image: $error",
                Modifier.align(Alignment.Center).padding(24.dp), color = Color.White,
            )
            else -> CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
    }
}

private fun decode(bytes: ByteArray): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 3072) sample *= 2
    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: throw IllegalStateException("Unsupported image")
    val orientation = try {
        ExifInterface(ByteArrayInputStream(bytes))
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } catch (_: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }
    val m = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
        else -> return bmp
    }
    return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
}
