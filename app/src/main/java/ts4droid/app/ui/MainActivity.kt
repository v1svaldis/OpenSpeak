package ts4droid.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ts4droid.app.data.Bookmark
import ts4droid.app.ts.ConnState
import ts4droid.app.ts.TsSession

class MainActivity : ComponentActivity() {
    private var pendingConnect: Bookmark? = null
    /** Bookmark prefilled from a ts3server:// link. */
    private val linkBookmark = mutableStateOf<Bookmark?>(null)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val b = pendingConnect
        pendingConnect = null
        if (result[Manifest.permission.RECORD_AUDIO] != false && b != null) TsSession.connect(b)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleLink(intent)
        setContent {
            MaterialTheme(colorScheme = AppColors) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    val state by TsSession.state.collectAsStateWithLifecycle()
                    when (val s = state) {
                        ConnState.Connected -> ServerScreen()
                        is ConnState.Connecting -> ConnectingScreen(s.step)
                        else -> ServerListScreen(
                            error = (s as? ConnState.Failed)?.reason,
                            linkBookmark = linkBookmark.value,
                            onLinkConsumed = { linkBookmark.value = null },
                            onConnect = ::connectWithPermissions,
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLink(intent)
    }

    private fun handleLink(intent: Intent?) {
        val uri: Uri = intent?.data ?: return
        if (uri.scheme != "ts3server" && uri.scheme != "teamspeak") return
        val host = uri.host ?: return
        val port = uri.getQueryParameter("port")
        linkBookmark.value = Bookmark(
            address = if (port != null) "$host:$port" else host,
            nickname = uri.getQueryParameter("nickname").orEmpty(),
            serverPassword = uri.getQueryParameter("password").orEmpty(),
            defaultChannel = uri.getQueryParameter("channel").orEmpty(),
            channelPassword = uri.getQueryParameter("channelpassword").orEmpty(),
            privilegeKey = uri.getQueryParameter("token").orEmpty(),
        )
    }

    private fun connectWithPermissions(b: Bookmark) {
        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) {
            TsSession.connect(b)
        } else {
            pendingConnect = b
            permissionLauncher.launch(needed.toTypedArray())
        }
    }
}

val AppColors = darkColorScheme(
    primary = Color(0xFF5BA4F5),
    onPrimary = Color(0xFF00213F),
    primaryContainer = Color(0xFF1E3A5F),
    onPrimaryContainer = Color(0xFFD4E3FF),
    secondary = Color(0xFF7FD48A),
    background = Color(0xFF101418),
    surface = Color(0xFF101418),
    surfaceVariant = Color(0xFF1D2329),
    surfaceContainer = Color(0xFF181D22),
    error = Color(0xFFFF8A80),
)

val TalkingGreen = Color(0xFF4CD964)
val MutedRed = Color(0xFFFF6B6B)
