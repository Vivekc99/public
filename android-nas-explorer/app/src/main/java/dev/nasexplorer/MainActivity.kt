package dev.nasexplorer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            NasTheme {
                Surface(color = MaterialTheme.colorScheme.background) { AppRoot(vm) }
            }
        }
    }
}

@Composable
fun NasTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(ctx)
        Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
fun AppRoot(vm: AppViewModel) {
    val ctx = LocalContext.current
    BackHandler(enabled = vm.canGoBack()) { vm.back() }

    LaunchedEffect(Unit) {
        for (intent in vm.intents) {
            try {
                ctx.startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(ctx, "No app found that can open this", Toast.LENGTH_LONG).show()
            }
        }
    }

    when (val o = vm.overlay) {
        is Overlay.Gallery -> GalleryScreen(vm, o)
        Overlay.Transfers -> TransfersScreen(vm)
        is Overlay.EditProfile -> EditProfileScreen(vm, o.profile)
        null -> if (vm.current == null) ProfilesScreen(vm) else BrowserScreen(vm)
    }
}
