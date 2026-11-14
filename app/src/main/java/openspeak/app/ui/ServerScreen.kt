package openspeak.app.ui

import android.app.Activity
import android.content.Context
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.HeadsetOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.StopScreenShare
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import openspeak.app.data.Bookmark
import openspeak.app.stream.TsStream
import openspeak.app.stream.WatchState
import openspeak.app.ts.TreeRow
import openspeak.app.ts.TsChannel
import openspeak.app.ts.TsClient
import openspeak.app.ts.TsSession
import openspeak.app.ts.buildTree

private enum class Page { Channels, Voice, Chat }

/** Everything shown while connected: channel list, voice room, chat and the stream viewer. */
@Composable
fun ServerScreen() {
    var page by rememberSaveable { mutableStateOf(Page.Channels) }
    var showSettings by remember { mutableStateOf(false) }
    var switchTo by remember { mutableStateOf<Bookmark?>(null) }
    var profile by remember { mutableStateOf<TsClient?>(null) }
    var fullscreenStream by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val watch by TsSession.streams.watch.collectAsStateWithLifecycle()
    val messages by TsSession.messages.collectAsStateWithLifecycle()
    var seenMessages by rememberSaveable { mutableIntStateOf(0) }
    if (page == Page.Chat) seenMessages = messages.size
    val unread = messages.size - seenMessages

    LaunchedEffect(Unit) { TsSession.notices.collect { snackbar.showSnackbar(it) } }
    // Starting to watch a stream jumps to the voice room where the video plays.
    LaunchedEffect(watch is WatchState.Connecting) { if (watch is WatchState.Connecting) page = Page.Voice }

    val screenShare = rememberScreenShareLauncher()

    BackHandler(page != Page.Channels) { page = if (page == Page.Chat) Page.Voice else Page.Channels }

    Box(Modifier.fillMaxSize()) {
        when (page) {
            Page.Channels -> ChannelsPage(
                onOpenVoice = { page = Page.Voice },
                onOpenChat = { page = Page.Chat },
                onSettings = { showSettings = true },
                onSwitchServer = { switchTo = it },
                onProfile = { profile = it },
                onScreenShare = screenShare,
                unread = unread,
            )
            Page.Voice -> VoiceRoomPage(
                onBack = { page = Page.Channels },
                onOpenChat = { page = Page.Chat },
                onProfile = { profile = it },
                onScreenShare = screenShare,
                onFullscreen = { fullscreenStream = true },
                unread = unread,
            )
            Page.Chat -> ChatPage(messages, onBack = { page = Page.Voice })
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 140.dp))
    }

    if (fullscreenStream && (watch is WatchState.Playing || watch is WatchState.Connecting))
        StreamFullscreen(watch, onClose = { fullscreenStream = false })
    profile?.let { c -> ProfileSheet(c, onDismiss = { profile = null }) }
    if (showSettings) SettingsPage(onDismiss = { showSettings = false })
    switchTo?.let { b ->
        AlertDialog(
            onDismissRequest = { switchTo = null },
            containerColor = Ds.Chat,
            title = { Text("Перейти на «${b.title}»?", color = Ds.Text) },
            text = { Text("Текущее подключение будет закрыто.", color = Ds.TextMuted) },
            confirmButton = {
                TextButton(onClick = { switchTo = null; TsSession.disconnect(); TsSession.connect(b) }) {
                    Text("Перейти", color = Ds.Blurple)
                }
            },
            dismissButton = { TextButton(onClick = { switchTo = null }) { Text("Отмена", color = Ds.TextMuted) } },
        )
    }
}

/** Returns an action that starts or stops sharing the screen, asking for the system's consent first. */
@Composable
fun rememberScreenShareLauncher(): () -> Unit {
    val context = LocalContext.current
    val publishing by TsSession.streams.publishing.collectAsStateWithLifecycle()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) TsSession.requestScreenShare(data)
    }
    return {
        if (publishing != null) {
            TsSession.stopScreenShare()
        } else {
            val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            launcher.launch(mpm.createScreenCaptureIntent())
        }
    }
}

// ------------------------------------------------------------------------------- channel list

@Composable
private fun ChannelsPage(
    onOpenVoice: () -> Unit,
    onOpenChat: () -> Unit,
    onSettings: () -> Unit,
    onSwitchServer: (Bookmark) -> Unit,
    onProfile: (TsClient) -> Unit,
    onScreenShare: () -> Unit,
    unread: Int,
) {
    val bookmarks = remember { TsSession.prefs.bookmarks }
    val current = TsSession.currentBookmark
    Row(Modifier.fillMaxSize()) {
        ServerRail(
            items = bookmarks.map { RailItem(it.id, it.title) },
            selected = current?.id, connected = current?.id,
            onSelect = { id -> if (id != current?.id) bookmarks.firstOrNull { it.id == id }?.let(onSwitchServer) },
            onAdd = {}, onSettings = onSettings,
        )
        Column(
            Modifier.fillMaxSize().clip(RoundedCornerShape(topStart = 16.dp)).background(Ds.Panel)
                .statusBarsPadding().navigationBarsPadding(),
        ) {
            ServerHeader(onOpenChat, unread)
            ChannelTree(Modifier.weight(1f), onProfile, onOpenVoice)
            VoiceConnectedBar(onOpenVoice, onScreenShare)
            UserPanel(onSettings)
        }
    }
}

@Composable
private fun ServerHeader(onOpenChat: () -> Unit, unread: Int) {
    val serverName by TsSession.serverName.collectAsStateWithLifecycle()
    val clients by TsSession.clients.collectAsStateWithLifecycle()
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                serverName.ifBlank { TsSession.currentBookmark?.title ?: "" }, style = MaterialTheme.typography.titleLarge,
                color = Ds.Text, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            val online = clients.values.count { it.type == 0 }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(Ds.Green))
                Spacer(Modifier.width(6.dp))
                Text("$online в сети", color = Ds.TextFaint, fontSize = 13.sp)
            }
        }
        BadgedIcon(Icons.Default.Forum, "Чат", unread, onOpenChat)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Ds.Rail))
}

@Composable
fun BadgedIcon(icon: ImageVector, description: String, count: Int, onClick: () -> Unit, tint: Color = Ds.TextMuted) {
    Box {
        IconButton(onClick = onClick) { Icon(icon, description, tint = tint) }
        if (count > 0) Box(
            Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 6.dp).size(18.dp).clip(CircleShape).background(Ds.Red),
            contentAlignment = Alignment.Center,
        ) { Text(if (count > 99) "99" else "$count", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun ChannelTree(modifier: Modifier, onProfile: (TsClient) -> Unit, onOpenVoice: () -> Unit) {
    val channels by TsSession.channels.collectAsStateWithLifecycle()
    val clients by TsSession.clients.collectAsStateWithLifecycle()
    val talking by TsSession.audio.talking.collectAsStateWithLifecycle()
    val transmitting by TsSession.audio.transmitting.collectAsStateWithLifecycle()
    val ownId by TsSession.ownId.collectAsStateWithLifecycle()
    val streams by TsSession.streams.streams.collectAsStateWithLifecycle()
    val streamers = remember(streams) { streams.values.map { it.clientId }.toSet() }
    val rows = remember(channels, clients) { buildTree(channels, clients) }
    val ownChannel = clients[ownId]?.channelId ?: 0
    var passwordFor by remember { mutableStateOf<TsChannel?>(null) }

    LazyColumn(modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)) {
        item { SectionLabel("Голосовые каналы", Modifier.padding(horizontal = 16.dp)) }
        items(rows, key = {
            when (it) {
                is TreeRow.ChannelRow -> "c${it.channel.id}"
                is TreeRow.ClientRow -> "u${it.client.id}"
            }
        }) { row ->
            when (row) {
                is TreeRow.ChannelRow -> ChannelItem(row, isOwn = row.channel.id == ownChannel) {
                    when {
                        row.channel.id == ownChannel -> onOpenVoice()
                        row.channel.hasPassword -> passwordFor = row.channel
                        else -> TsSession.joinChannel(row.channel.id)
                    }
                }
                is TreeRow.ClientRow -> ClientItem(
                    row,
                    isOwn = row.client.id == ownId,
                    isTalking = if (row.client.id == ownId) transmitting else row.client.id in talking,
                    isLive = row.client.id in streamers,
                ) { onProfile(row.client) }
            }
        }
    }

    passwordFor?.let { ch ->
        var pw by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { passwordFor = null },
            containerColor = Ds.Chat,
            title = { Text("Пароль канала «${ch.name}»", color = Ds.Text) },
            text = { Column { DsField("Пароль", pw, { pw = it }, password = true) } },
            confirmButton = {
                TextButton(onClick = { TsSession.joinChannel(ch.id, pw); passwordFor = null }) { Text("Войти", color = Ds.Blurple) }
            },
            dismissButton = { TextButton(onClick = { passwordFor = null }) { Text("Отмена", color = Ds.TextMuted) } },
        )
    }
}

@Composable
private fun ChannelItem(row: TreeRow.ChannelRow, isOwn: Boolean, onClick: () -> Unit) {
    val ch = row.channel
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 8.dp + 16.dp * row.depth, end = 8.dp, top = 1.dp, bottom = 1.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (isOwn) Ds.Selected else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (ch.hasPassword) Icons.Default.Lock else Icons.AutoMirrored.Filled.VolumeUp, null, Modifier.size(20.dp),
            tint = if (isOwn) Ds.Text else Ds.TextFaint,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            ch.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (isOwn) Ds.Text else Ds.TextFaint, fontSize = 16.sp,
            fontWeight = if (isOwn) FontWeight.SemiBold else FontWeight.Medium,
        )
        if (ch.maxClients > 0) Text(
            "${row.userCount}/${ch.maxClients}", color = Ds.TextFaint, fontSize = 12.sp,
            modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Ds.Rail).padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun ClientItem(row: TreeRow.ClientRow, isOwn: Boolean, isTalking: Boolean, isLive: Boolean, onClick: () -> Unit) {
    val c = row.client
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 36.dp + 16.dp * (row.depth - 1), end = 8.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(c.nickname, 26.dp, talking = isTalking)
        Spacer(Modifier.width(8.dp))
        Text(
            c.nickname, Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (c.away) Ds.TextFaint else if (isOwn) Ds.Text else Ds.TextMuted, fontSize = 15.sp,
        )
        if (isLive) {
            Spacer(Modifier.width(6.dp))
            LiveBadge()
        }
        Spacer(Modifier.weight(1f))
        if (c.inputMuted) Icon(Icons.Default.MicOff, "Микрофон выключен", Modifier.size(16.dp), tint = Ds.TextFaint)
        if (c.outputMuted) Icon(Icons.Default.HeadsetOff, "Звук выключен", Modifier.padding(start = 4.dp).size(16.dp), tint = Ds.TextFaint)
    }
}

/** "Voice connected" strip above the user panel, as in Discord. */
@Composable
private fun VoiceConnectedBar(onOpenVoice: () -> Unit, onScreenShare: () -> Unit) {
    val channels by TsSession.channels.collectAsStateWithLifecycle()
    val clients by TsSession.clients.collectAsStateWithLifecycle()
    val ownId by TsSession.ownId.collectAsStateWithLifecycle()
    val publishing by TsSession.streams.publishing.collectAsStateWithLifecycle()
    val channel = channels[clients[ownId]?.channelId ?: 0] ?: return
    Box(Modifier.fillMaxWidth().height(1.dp).background(Ds.Rail))
    Row(
        Modifier.fillMaxWidth().background(Ds.Rail.copy(alpha = 0.6f)).clickable(onClick = onOpenVoice)
            .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.SignalCellularAlt, null, tint = Ds.Green, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(if (publishing != null) "Демонстрация экрана" else "Голосовая связь подключена", color = Ds.Green, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(channel.name, color = Ds.TextFaint, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onScreenShare) {
            Icon(
                if (publishing != null) Icons.Default.StopScreenShare else Icons.Default.ScreenShare,
                "Демонстрация экрана", tint = if (publishing != null) Ds.Green else Ds.TextMuted,
            )
        }
        IconButton(onClick = { TsSession.disconnect() }) { Icon(Icons.Default.CallEnd, "Отключиться", tint = Ds.TextMuted) }
    }
}

@Composable
private fun UserPanel(onSettings: () -> Unit) {
    val clients by TsSession.clients.collectAsStateWithLifecycle()
    val ownId by TsSession.ownId.collectAsStateWithLifecycle()
    val inputMuted by TsSession.inputMuted.collectAsStateWithLifecycle()
    val outputMuted by TsSession.outputMuted.collectAsStateWithLifecycle()
    val transmitting by TsSession.audio.transmitting.collectAsStateWithLifecycle()
    val me = clients[ownId]
    Row(
        Modifier.fillMaxWidth().background(Ds.Rail).padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(me?.nickname ?: "?", 34.dp, talking = transmitting)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(me?.nickname ?: "", color = Ds.Text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                when {
                    outputMuted -> "Звук выключен"
                    inputMuted -> "Микрофон выключен"
                    else -> "В сети"
                },
                color = Ds.TextFaint, fontSize = 12.sp,
            )
        }
        IconButton(onClick = { TsSession.setInputMuted(!inputMuted) }) {
            Icon(if (inputMuted) Icons.Default.MicOff else Icons.Default.Mic, "Микрофон", tint = if (inputMuted) Ds.Red else Ds.TextMuted)
        }
        IconButton(onClick = { TsSession.setOutputMuted(!outputMuted) }) {
            Icon(if (outputMuted) Icons.Default.HeadsetOff else Icons.Default.Headset, "Звук", tint = if (outputMuted) Ds.Red else Ds.TextMuted)
        }
        IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Настройки", tint = Ds.TextMuted) }
    }
}

/** Streams belonging to a client, if any. */
fun streamOf(streams: Map<String, TsStream>, clientId: Int): TsStream? = streams.values.firstOrNull { it.clientId == clientId }
