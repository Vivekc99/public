package dev.nasexplorer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.view.LayoutInflater
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material3.TextButton
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
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
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
    var playError by remember { mutableStateOf<String?>(null) }
    val player = remember {
        ExoPlayer.Builder(ctx, DefaultRenderersFactory(ctx).setEnableDecoderFallback(true))
            .setMediaSourceFactory(DefaultMediaSourceFactory(SmbDataSource.Factory(vm.pool)))
            .build()
    }

    DisposableEffect(Unit) {
        val window = (ctx as ComponentActivity).window
        val ctl = WindowInsetsControllerCompat(window, window.decorView)
        ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ctl.hide(WindowInsetsCompat.Type.systemBars())
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                val detail = generateSequence<Throwable>(error) { it.cause }
                    .mapNotNull { it.message }.distinct().joinToString("\n")
                playError = (error.errorCodeName + "\n" + detail).take(600)
            }

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
        playError = null
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
                Kind.Video, Kind.Audio -> MediaPage(vm, player, e, active = i == pager.settledPage, error = playError)
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
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
            val cur = items[pager.currentPage]
            if (kindOf(cur.name) == Kind.Video) {
                TextButton(onClick = { vm.openInPlayer(cur) }) { Text("Player ↗", color = Color.White) }
            }
        }
    }
}

@Composable
private fun MediaPage(vm: AppViewModel, player: ExoPlayer, e: NasEntry, active: Boolean, error: String?) {
    if (active && error != null) {
        Column(
            Modifier.fillMaxSize().background(Color.Black).padding(24.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("⚠️ Can't play this file here", color = Color.White, fontSize = 20.sp)
            Spacer(Modifier.height(10.dp))
            Text(
                "Playback failed. If this is 10-bit 4:2:2 footage (e.g. from a Sony camera) the phone's " +
                    "built-in decoder can't play it. VLC for Android is free and can usually play it.",
                color = Color.LightGray, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                Button(onClick = { vm.openInPlayer(e) }) { Text("Open in VLC / other player") }
                OutlinedButton(onClick = { vm.saveOne(e) }) { Text("⬇ Save") }
            }
            Spacer(Modifier.height(18.dp))
            Text(error, color = Color.Gray, fontSize = 11.sp, maxLines = 8, overflow = TextOverflow.Ellipsis)
        }
    } else if (active) {
        val zoom = remember(e.path) { ZoomState() }
        AndroidView(
            modifier = Modifier.fillMaxSize().background(Color.Black).pinchZoom(zoom),
            factory = { c ->
                (LayoutInflater.from(c).inflate(R.layout.player_view, null, false) as PlayerView).apply {
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

@Composable
private fun ImagePage(vm: AppViewModel, profile: Profile, entry: NasEntry) {
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val zoom = remember { ZoomState() }

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
                    .pointerInput(Unit) { detectTapGestures(onDoubleTap = { zoom.toggle() }) }
                    .pinchZoom(zoom),
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
    return rotateByExif(bmp, orientation)
}
