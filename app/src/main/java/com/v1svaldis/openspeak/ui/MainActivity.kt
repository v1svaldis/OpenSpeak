package com.v1svaldis.openspeak.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v1svaldis.openspeak.data.Bookmark
import com.v1svaldis.openspeak.ts.ConnState
import com.v1svaldis.openspeak.ts.TsSession

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
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Ds.Rail.toArgbInt()),
            navigationBarStyle = SystemBarStyle.dark(Ds.Rail.toArgbInt()),
        )
        handleLink(intent)
        setContent {
            AppTheme {
                Surface(color = Ds.Rail) {
                    val state by TsSession.state.collectAsStateWithLifecycle()
                    when (val s = state) {
                        ConnState.Connected -> ServerScreen()
                        is ConnState.Connecting -> ConnectingScreen(s.step)
                        else -> HomeScreen(
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
            id = 0,
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

private fun androidx.compose.ui.graphics.Color.toArgbInt(): Int = android.graphics.Color.argb(
    (alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt()
)

val TalkingGreen = Ds.Green
val MutedRed = Ds.Red
