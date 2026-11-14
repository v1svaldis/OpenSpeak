package openspeak.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.HeadsetOff
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.StopScreenShare
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import openspeak.app.data.TalkMode
import openspeak.app.stream.TsStream
import openspeak.app.stream.WatchState
import openspeak.app.ts.TsClient
import openspeak.app.ts.TsSession

/** Discord-style call screen of the channel we are in: participant tiles, the watched stream, call controls. */
@Composable
fun VoiceRoomPage(
    onBack: () -> Unit,
    onOpenChat: () -> Unit,
    onProfile: (TsClient) -> Unit,
    onScreenShare: () -> Unit,
    onFullscreen: () -> Unit,
    unread: Int,
) {
    val channels by TsSession.channels.collectAsStateWithLifecycle()
    val clients by TsSession.clients.collectAsStateWithLifecycle()
    val ownId by TsSession.ownId.collectAsStateWithLifecycle()
    val talking by TsSession.audio.talking.collectAsStateWithLifecycle()
    val transmitting by TsSession.audio.transmitting.collectAsStateWithLifecycle()
    val streams by TsSession.streams.streams.collectAsStateWithLifecycle()
    val watch by TsSession.streams.watch.collectAsStateWithLifecycle()
    val publishing by TsSession.streams.publishing.collectAsStateWithLifecycle()
    val viewers by TsSession.streams.viewers.collectAsStateWithLifecycle()
    val ownChannel = clients[ownId]?.channelId ?: 0
    val members = clients.values.filter { it.channelId == ownChannel && it.type == 0 }
        .sortedWith(compareBy<TsClient> { it.id != ownId }.thenBy { it.nickname.lowercase() })

    Column(Modifier.fillMaxSize().background(Ds.Call).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", tint = Ds.TextMuted) }
            Icon(Icons.AutoMirrored.Filled.VolumeUp, null, tint = Ds.TextFaint, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                channels[ownChannel]?.name ?: "", color = Ds.Text, fontWeight = FontWeight.Bold, fontSize = 17.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            BadgedIcon(Icons.Default.Forum, "Чат", unread, onOpenChat)
        }

        LazyVerticalGrid(
            GridCells.Fixed(2), Modifier.weight(1f).padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (watch !is WatchState.Idle) item(span = { GridItemSpan(2) }) {
                WatchedStream(watch, clients, onFullscreen)
            }
            if (publishing != null) item(span = { GridItemSpan(2) }) {
                OwnShareTile(viewers.mapNotNull { clients[it]?.nickname }, onScreenShare)
            }
            items(members, key = { it.id }) { c ->
                val stream = if (c.id == ownId) null else streamOf(streams, c.id)
                ParticipantTile(
                    c, talking = if (c.id == ownId) transmitting else c.id in talking,
                    stream = stream,
                    watching = stream != null && (watch as? WatchState.Playing)?.stream?.id == stream.id,
                    onClick = { onProfile(c) },
                )
            }
        }

        CallControls(onScreenShare, publishing != null)
    }
}

@Composable
private fun ParticipantTile(c: TsClient, talking: Boolean, stream: TsStream?, watching: Boolean, onClick: () -> Unit) {
    val border by animateColorAsState(if (talking) Ds.Green else Color.Transparent, label = "talk")
    Box(
        Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(Ds.Tile)
            .border(2.dp, border, RoundedCornerShape(12.dp)).clickable(onClick = onClick),
    ) {
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Avatar(c.nickname, 64.dp)
            if (stream != null) {
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier.clip(RoundedCornerShape(16.dp)).background(if (watching) Ds.Input else Ds.Blurple)
                        .clickable { if (watching) TsSession.streams.stopWatching() else TsSession.streams.watch(stream) }
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    Text(if (watching) "Не смотреть" else "Смотреть", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
            }
        }
        Row(
            Modifier.align(Alignment.BottomStart).padding(8.dp).clip(RoundedCornerShape(6.dp))
                .background(Color.Black.copy(alpha = 0.5f)).padding(horizontal = 6.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (c.inputMuted) Icon(Icons.Default.MicOff, null, tint = Ds.Red, modifier = Modifier.size(14.dp).padding(end = 2.dp))
            if (c.outputMuted) Icon(Icons.Default.HeadsetOff, null, tint = Ds.Red, modifier = Modifier.size(14.dp).padding(end = 2.dp))
            Text(c.nickname, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (stream != null) LiveBadge(Modifier.align(Alignment.TopEnd).padding(8.dp))
    }
}

@Composable
private fun OwnShareTile(viewerNames: List<String>, onStop: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Ds.Tile).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Default.ScreenShare, null, tint = Ds.Green, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(8.dp))
        Text("Вы показываете свой экран", color = Ds.Text, fontWeight = FontWeight.SemiBold)
        Text(
            if (viewerNames.isEmpty()) "Пока никто не смотрит" else "Смотрят: " + viewerNames.joinToString(),
            color = Ds.TextMuted, fontSize = 13.sp, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        BlurpleButton("Остановить показ", onStop, color = Ds.Red)
    }
}

@Composable
private fun WatchedStream(watch: WatchState, clients: Map<Int, TsClient>, onFullscreen: () -> Unit) {
    // The box follows the video's shape: wide for a PC screen, tall for a phone.
    var aspect by remember { mutableStateOf(16f / 10f) }
    Box(
        Modifier.fillMaxWidth().aspectRatio(aspect.coerceIn(0.75f, 2.4f)).clip(RoundedCornerShape(12.dp))
            .background(Color.Black),
    ) {
        StreamContent(watch, clients, onAspect = { aspect = it })
        Row(Modifier.align(Alignment.TopEnd).padding(4.dp)) {
            if (watch is WatchState.Playing) IconButton(onClick = onFullscreen) {
                Icon(Icons.Default.Fullscreen, "Во весь экран", tint = Color.White)
            }
            IconButton(onClick = { TsSession.streams.stopWatching() }) { Icon(Icons.Default.Close, "Закрыть", tint = Color.White) }
        }
    }
}

/** Video of the watched stream, or its connecting / ended state. */
@Composable
fun StreamContent(watch: WatchState, clients: Map<Int, TsClient>, onAspect: (Float) -> Unit = {}) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (watch) {
            is WatchState.Playing -> {
                VideoView(watch.track, Modifier.fillMaxSize(), onAspect)
                Row(
                    Modifier.align(Alignment.TopStart).padding(10.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    LiveBadge()
                    Spacer(Modifier.width(6.dp))
                    Text(clients[watch.stream.clientId]?.nickname ?: watch.stream.name, color = Color.White, fontSize = 13.sp)
                }
            }
            is WatchState.Connecting -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Ds.Blurple, modifier = Modifier.size(32.dp))
                Spacer(Modifier.height(12.dp))
                Text(watch.step, color = Ds.TextMuted, fontSize = 13.sp)
            }
            is WatchState.Ended -> Text(watch.reason, color = Ds.TextMuted, fontSize = 14.sp, textAlign = TextAlign.Center)
            WatchState.Idle -> {}
        }
    }
}

/** libwebrtc's SurfaceViewRenderer bound to a remote video track. */
@Composable
fun VideoView(track: VideoTrack, modifier: Modifier, onAspect: (Float) -> Unit = {}) {
    val egl = TsSession.streams.eglBase
    var renderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    // libwebrtc's renderer always fills (crops to) its own view, so letterboxing means giving the
    // view itself the video's aspect ratio and centring it on a black background.
    var videoAspect by remember { mutableStateOf(16f / 9f) }
    val mainHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { ctx ->
                SurfaceViewRenderer(ctx).apply {
                    init(egl.eglBaseContext, object : RendererCommon.RendererEvents {
                        override fun onFirstFrameRendered() {}
                        override fun onFrameResolutionChanged(width: Int, height: Int, rotation: Int) {
                            val (w, h) = if (rotation % 180 == 0) width to height else height to width
                            if (w > 0 && h > 0) mainHandler.post {
                                videoAspect = w.toFloat() / h
                                onAspect(videoAspect)
                            }
                        }
                    })
                    setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                    setEnableHardwareScaler(true)
                    renderer = this
                }
            },
            onRelease = { it.release() },
            modifier = Modifier.aspectRatio(videoAspect, matchHeightConstraintsFirst = true),
        )
    }
    val r = renderer
    DisposableEffect(track, r) {
        if (r != null) track.addSink(r)
        onDispose { if (r != null) runCatching { track.removeSink(r) } }
    }
}

@Composable
private fun CallControls(onScreenShare: () -> Unit, sharing: Boolean) {
    val inputMuted by TsSession.inputMuted.collectAsStateWithLifecycle()
    val transmitting by TsSession.audio.transmitting.collectAsStateWithLifecycle()
    var speaker by remember { mutableStateOf(TsSession.prefs.speakerphone) }
    var mode by remember { mutableStateOf(TsSession.prefs.talkMode) }

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)).background(Ds.Panel)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        if (mode == TalkMode.PUSH_TO_TALK) {
            PushToTalkButton(enabled = !inputMuted, active = transmitting)
            Spacer(Modifier.height(12.dp))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            RoundControl(
                if (inputMuted) Icons.Default.MicOff else Icons.Default.Mic, "Микрофон",
                active = inputMuted, activeColor = Color.White, activeIcon = Ds.Red,
            ) { TsSession.setInputMuted(!inputMuted) }
            RoundControl(
                if (speaker) Icons.AutoMirrored.Filled.VolumeUp else Icons.Default.PhoneInTalk, "Громкая связь",
                active = speaker,
            ) {
                speaker = !speaker
                TsSession.prefs.speakerphone = speaker
                TsSession.audio.setSpeakerphone(speaker)
            }
            RoundControl(
                if (sharing) Icons.Default.StopScreenShare else Icons.Default.ScreenShare, "Демонстрация экрана",
                active = sharing, activeColor = Ds.Green,
            ) { onScreenShare() }
            Box(
                Modifier.size(52.dp).clip(CircleShape).background(Ds.Input).clickable {
                    mode = TalkMode.entries[(mode.ordinal + 1) % TalkMode.entries.size]
                    TsSession.prefs.talkMode = mode
                    TsSession.audio.talkMode = mode
                },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    when (mode) {
                        TalkMode.PUSH_TO_TALK -> "PTT"
                        TalkMode.VOICE_ACTIVATION -> "VAD"
                        TalkMode.CONTINUOUS -> "ON"
                    },
                    color = Ds.Text, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                )
            }
            RoundControl(Icons.Default.CallEnd, "Отключиться", active = true, activeColor = Ds.Red, activeIcon = Color.White) {
                TsSession.disconnect()
            }
        }
    }
}

@Composable
private fun RoundControl(
    icon: ImageVector, description: String, active: Boolean,
    activeColor: Color = Color.White, activeIcon: Color = Color.Black, onClick: () -> Unit,
) {
    Box(
        Modifier.size(52.dp).clip(CircleShape).background(if (active) activeColor else Ds.Input).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, tint = if (active) activeIcon else Ds.Text) }
}

@Composable
private fun PushToTalkButton(enabled: Boolean, active: Boolean) {
    val bg by animateColorAsState(
        when {
            !enabled -> Ds.Input
            active -> Ds.Green
            else -> Ds.Blurple
        }, label = "ptt",
    )
    Box(
        Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(32.dp)).background(bg)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(onPress = {
                    TsSession.audio.pushToTalkPressed = true
                    try {
                        tryAwaitRelease()
                    } finally {
                        TsSession.audio.pushToTalkPressed = false
                    }
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Mic, null, tint = Color.White)
            Spacer(Modifier.width(8.dp))
            Text(
                if (!enabled) "Микрофон выключен" else if (active) "Говорите…" else "Удерживайте, чтобы говорить",
                fontWeight = FontWeight.SemiBold, color = Color.White,
            )
        }
    }
}

/** The watched stream on the whole screen; tap toggles the overlay. */
@Composable
fun StreamFullscreen(watch: WatchState, onClose: () -> Unit) {
    val clients by TsSession.clients.collectAsStateWithLifecycle()
    var overlay by remember { mutableStateOf(true) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) { detectTapGestures { overlay = !overlay } }) {
            StreamContent(watch, clients)
            if (overlay) IconButton(
                onClick = onClose, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp),
            ) { Icon(Icons.Default.Close, "Закрыть", tint = Color.White) }
        }
    }
}
