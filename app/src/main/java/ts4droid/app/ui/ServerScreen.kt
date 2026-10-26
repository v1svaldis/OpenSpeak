package ts4droid.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.HeadsetOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ts4droid.app.data.TalkMode
import ts4droid.app.ts.ChatMessage
import ts4droid.app.ts.TreeRow
import ts4droid.app.ts.TsChannel
import ts4droid.app.ts.TsClient
import ts4droid.app.ts.TsSession
import ts4droid.app.ts.buildTree
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerScreen() {
    val serverName by TsSession.serverName.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showSettings by remember { mutableStateOf(false) }
    val messages by TsSession.messages.collectAsStateWithLifecycle()
    var seenMessages by rememberSaveable { mutableIntStateOf(0) }
    if (tab == 1) seenMessages = messages.size

    LaunchedEffect(Unit) {
        TsSession.notices.collect { snackbar.showSnackbar(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        serverName.ifBlank { TsSession.currentBookmark?.title ?: "" },
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                },
                actions = {
                    IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, "Настройки") }
                    IconButton(onClick = { TsSession.disconnect() }) {
                        Icon(Icons.Default.CallEnd, "Отключиться", tint = MutedRed)
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = { VoiceControls() },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                Tab(tab == 0, { tab = 0 }, text = { Text("Каналы") })
                val unread = messages.size - seenMessages
                Tab(tab == 1, { tab = 1 }, text = { Text(if (unread > 0 && tab != 1) "Чат ($unread)" else "Чат") })
            }
            when (tab) {
                0 -> ChannelTree()
                else -> ChatPane(messages)
            }
        }
    }

    if (showSettings) SettingsDialog(onDismiss = { showSettings = false })
}

@Composable
private fun ChannelTree() {
    val channels by TsSession.channels.collectAsStateWithLifecycle()
    val clients by TsSession.clients.collectAsStateWithLifecycle()
    val talking by TsSession.audio.talking.collectAsStateWithLifecycle()
    val transmitting by TsSession.audio.transmitting.collectAsStateWithLifecycle()
    val ownId by TsSession.ownId.collectAsStateWithLifecycle()
    val rows = remember(channels, clients) { buildTree(channels, clients) }
    val ownChannel = clients[ownId]?.channelId ?: 0

    var passwordFor by remember { mutableStateOf<TsChannel?>(null) }
    var clientMenu by remember { mutableStateOf<TsClient?>(null) }

    LazyColumn(Modifier.fillMaxSize()) {
        items(rows, key = {
            when (it) {
                is TreeRow.ChannelRow -> "c${it.channel.id}"
                is TreeRow.ClientRow -> "u${it.client.id}"
            }
        }) { row ->
            when (row) {
                is TreeRow.ChannelRow -> ChannelItem(row, isOwn = row.channel.id == ownChannel) {
                    if (row.channel.id == ownChannel) return@ChannelItem
                    if (row.channel.hasPassword) passwordFor = row.channel
                    else TsSession.joinChannel(row.channel.id)
                }
                is TreeRow.ClientRow -> ClientItem(
                    row,
                    isOwn = row.client.id == ownId,
                    isTalking = if (row.client.id == ownId) transmitting else row.client.id in talking,
                ) { if (row.client.id != ownId) clientMenu = row.client }
            }
        }
    }

    passwordFor?.let { ch ->
        var pw by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { passwordFor = null },
            title = { Text("Пароль канала «${ch.name}»") },
            text = { OutlinedTextField(pw, { pw = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = { TsSession.joinChannel(ch.id, pw); passwordFor = null }) { Text("Войти") }
            },
            dismissButton = { TextButton(onClick = { passwordFor = null }) { Text("Отмена") } },
        )
    }

    clientMenu?.let { c ->
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { clientMenu = null },
            title = { Text(c.nickname) },
            text = {
                Column {
                    if (c.away) Text("Отошёл: ${c.awayMessage}", style = MaterialTheme.typography.bodySmall)
                    if (c.platform.isNotBlank()) Text("Платформа: ${c.platform}", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(text, { text = it }, label = { Text("Сообщение") })
                }
            },
            confirmButton = {
                TextButton(enabled = text.isNotBlank(), onClick = {
                    TsSession.sendPrivateMessage(c.id, text); clientMenu = null
                }) { Text("Личное сообщение") }
            },
            dismissButton = {
                TextButton(onClick = { TsSession.poke(c.id, text); clientMenu = null }) { Text("Poke") }
            },
        )
    }
}

@Composable
private fun ChannelItem(row: TreeRow.ChannelRow, isOwn: Boolean, onClick: () -> Unit) {
    val ch = row.channel
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(if (isOwn) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else Color.Transparent)
            .padding(start = 12.dp + 18.dp * row.depth, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Folder, null, Modifier.size(20.dp),
            tint = if (isOwn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            ch.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            fontWeight = if (isOwn) FontWeight.SemiBold else FontWeight.Normal,
        )
        if (ch.hasPassword) Icon(Icons.Default.Lock, "Пароль", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        if (row.userCount > 0) {
            Spacer(Modifier.width(8.dp))
            Text("${row.userCount}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ClientItem(row: TreeRow.ClientRow, isOwn: Boolean, isTalking: Boolean, onClick: () -> Unit) {
    val c = row.client
    val dot by animateColorAsState(
        when {
            isTalking -> TalkingGreen
            c.inputMuted || c.outputMuted -> MutedRed
            else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        }, label = "talk",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 12.dp + 18.dp * row.depth, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(22.dp).clip(CircleShape).background(dot.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
            Icon(
                when {
                    c.outputMuted -> Icons.Default.HeadsetOff
                    c.inputMuted -> Icons.Default.MicOff
                    else -> Icons.Default.Person
                }, null, Modifier.size(15.dp), tint = dot,
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            c.nickname + if (c.away) "  (отошёл)" else "",
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            fontWeight = if (isOwn) FontWeight.Bold else FontWeight.Normal,
            color = if (c.away) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ChatPane(messages: List<ChatMessage>) {
    val listState = rememberLazyListState()
    var text by rememberSaveable { mutableStateOf("") }
    val fmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1) }

    Column(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
            items(messages) { m ->
                val scope = when (m.targetMode) {
                    1 -> "лично"
                    2 -> "канал"
                    3 -> "сервер"
                    else -> ""
                }
                Column(Modifier.padding(vertical = 4.dp)) {
                    Row {
                        Text(fmt.format(Date(m.time)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(6.dp))
                        if (m.fromName.isNotBlank())
                            Text(m.fromName, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                                color = if (m.own) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary)
                        if (scope.isNotEmpty()) {
                            Spacer(Modifier.width(6.dp))
                            Text("· $scope", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Text(m.text.stripBbCode())
                }
            }
        }
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(text, { text = it }, Modifier.weight(1f), placeholder = { Text("Сообщение в канал") }, maxLines = 4)
            IconButton(enabled = text.isNotBlank(), onClick = {
                TsSession.sendChannelMessage(text.trim()); text = ""
            }) { Icon(Icons.AutoMirrored.Filled.Send, "Отправить") }
        }
    }
}

/** TeamSpeak messages use BBCode ([b], [url=...], [color=...]); show them as plain text. */
private fun String.stripBbCode(): String =
    replace(Regex("\\[url=([^\\]]*)\\](.*?)\\[/url\\]", RegexOption.IGNORE_CASE), "$2 ($1)")
        .replace(Regex("\\[/?[a-z]+(=[^\\]]*)?\\]", RegexOption.IGNORE_CASE), "")

@Composable
private fun VoiceControls() {
    val inputMuted by TsSession.inputMuted.collectAsStateWithLifecycle()
    val outputMuted by TsSession.outputMuted.collectAsStateWithLifecycle()
    val transmitting by TsSession.audio.transmitting.collectAsStateWithLifecycle()
    val level by TsSession.audio.inputLevelDb.collectAsStateWithLifecycle()
    var speaker by remember { mutableStateOf(TsSession.prefs.speakerphone) }
    var mode by remember { mutableStateOf(TsSession.prefs.talkMode) }

    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 3.dp) {
        Column(Modifier.navigationBarsPadding().padding(12.dp)) {
            // Microphone level meter with the voice-activation threshold in mind.
            LinearProgressIndicator(
                progress = { ((level + 70f) / 70f).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                color = if (transmitting) TalkingGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledIconToggleButton(checked = inputMuted, onCheckedChange = { TsSession.setInputMuted(it) }) {
                    Icon(if (inputMuted) Icons.Default.MicOff else Icons.Default.Mic, "Микрофон")
                }
                FilledIconToggleButton(checked = outputMuted, onCheckedChange = { TsSession.setOutputMuted(it) }) {
                    Icon(if (outputMuted) Icons.Default.HeadsetOff else Icons.Default.Headset, "Звук")
                }
                FilledIconToggleButton(checked = speaker, onCheckedChange = {
                    speaker = it
                    TsSession.prefs.speakerphone = it
                    TsSession.audio.setSpeakerphone(it)
                }) {
                    Icon(if (speaker) Icons.Default.VolumeUp else Icons.Default.PhoneInTalk, "Громкая связь")
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    mode = TalkMode.entries[(mode.ordinal + 1) % TalkMode.entries.size]
                    TsSession.prefs.talkMode = mode
                    TsSession.audio.talkMode = mode
                }) {
                    Text(
                        when (mode) {
                            TalkMode.PUSH_TO_TALK -> "PTT"
                            TalkMode.VOICE_ACTIVATION -> "Голос"
                            TalkMode.CONTINUOUS -> "Всегда"
                        }
                    )
                }
            }
            if (mode == TalkMode.PUSH_TO_TALK) {
                Spacer(Modifier.height(10.dp))
                PushToTalkButton(enabled = !inputMuted, active = transmitting)
            }
        }
    }
}

@Composable
private fun PushToTalkButton(enabled: Boolean, active: Boolean) {
    val bg by animateColorAsState(
        when {
            !enabled -> MaterialTheme.colorScheme.surfaceVariant
            active -> TalkingGreen
            else -> MaterialTheme.colorScheme.primaryContainer
        }, label = "ptt",
    )
    Box(
        Modifier
            .fillMaxWidth()
            .height(72.dp)
            .clip(RoundedCornerShape(36.dp))
            .background(bg)
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
            Icon(Icons.Default.Mic, null, tint = if (active) Color.Black else MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.width(8.dp))
            Text(
                if (!enabled) "Микрофон выключен" else if (active) "Говорите…" else "Удерживайте, чтобы говорить",
                fontWeight = FontWeight.SemiBold,
                color = if (active) Color.Black else MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}
