package ts4droid.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import ts4droid.app.data.Bookmark
import ts4droid.app.data.TalkMode
import ts4droid.app.ts.TsSession

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerListScreen(
    error: String?,
    linkBookmark: Bookmark?,
    onLinkConsumed: () -> Unit,
    onConnect: (Bookmark) -> Unit,
) {
    val prefs = TsSession.prefs
    var bookmarks by remember { mutableStateOf(prefs.bookmarks) }
    var editing by remember { mutableStateOf<Bookmark?>(null) }
    var showSettings by remember { mutableStateOf(false) }

    LaunchedEffect(linkBookmark) {
        if (linkBookmark != null) {
            editing = linkBookmark
            onLinkConsumed()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("TS4Droid") },
                actions = {
                    IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, "Настройки") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { editing = Bookmark(id = 0) },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("Сервер") },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (error != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth().padding(16.dp, 8.dp),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.ErrorOutline, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                        Spacer(Modifier.width(12.dp))
                        Text(error, Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
                        TextButton(onClick = { TsSession.acknowledge() }) { Text("OK") }
                    }
                }
            }
            if (bookmarks.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "Добавьте сервер TeamSpeak 6 (или 3) кнопкой «Сервер».\nАдрес: host или host:порт (по умолчанию UDP 9987).",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(bookmarks, key = { it.id }) { b ->
                    Card(
                        onClick = { onConnect(b) },
                        modifier = Modifier.fillMaxWidth().padding(16.dp, 6.dp),
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Dns, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text(b.title, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "${b.address} · ${b.nickname.ifBlank { "Android" }}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { editing = b }) { Icon(Icons.Default.Edit, "Изменить") }
                        }
                    }
                }
            }
        }
    }

    editing?.let { b ->
        BookmarkDialog(
            initial = b,
            onDismiss = { editing = null },
            onSave = { saved, connect ->
                val list = bookmarks.toMutableList()
                val stored = if (saved.id == 0L) saved.copy(id = System.currentTimeMillis()) else saved
                val idx = list.indexOfFirst { it.id == stored.id }
                if (idx >= 0) list[idx] = stored else list += stored
                prefs.bookmarks = list
                bookmarks = list
                editing = null
                if (connect) onConnect(stored)
            },
            onDelete = if (b.id != 0L && bookmarks.any { it.id == b.id }) {
                {
                    val list = bookmarks.filter { it.id != b.id }
                    prefs.bookmarks = list
                    bookmarks = list
                    editing = null
                }
            } else null,
        )
    }

    if (showSettings) SettingsDialog(onDismiss = { showSettings = false })
}

@Composable
private fun BookmarkDialog(
    initial: Bookmark,
    onDismiss: () -> Unit,
    onSave: (Bookmark, connect: Boolean) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var label by remember { mutableStateOf(initial.label) }
    var address by remember { mutableStateOf(initial.address) }
    var nickname by remember { mutableStateOf(initial.nickname) }
    var serverPassword by remember { mutableStateOf(initial.serverPassword) }
    var channel by remember { mutableStateOf(initial.defaultChannel) }
    var channelPassword by remember { mutableStateOf(initial.channelPassword) }
    var token by remember { mutableStateOf(initial.privilegeKey) }

    fun result() = initial.copy(
        label = label.trim(), address = address.trim(), nickname = nickname.trim(),
        serverPassword = serverPassword, defaultChannel = channel.trim(),
        channelPassword = channelPassword, privilegeKey = token.trim(),
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.address.isBlank()) "Новый сервер" else "Сервер") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(address, { address = it }, label = { Text("Адрес (host[:порт])") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                OutlinedTextField(nickname, { nickname = it }, label = { Text("Ник") }, singleLine = true)
                OutlinedTextField(label, { label = it }, label = { Text("Название (необязательно)") }, singleLine = true)
                OutlinedTextField(serverPassword, { serverPassword = it }, label = { Text("Пароль сервера") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation())
                OutlinedTextField(channel, { channel = it }, label = { Text("Канал при входе (необязательно)") }, singleLine = true)
                OutlinedTextField(channelPassword, { channelPassword = it }, label = { Text("Пароль канала") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation())
                OutlinedTextField(token, { token = it }, label = { Text("Ключ привилегий (token)") }, singleLine = true)
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Удалить")
                    }
                }
            }
        },
        confirmButton = {
            Button(enabled = address.isNotBlank(), onClick = { onSave(result(), true) }) { Text("Подключиться") }
        },
        dismissButton = {
            TextButton(enabled = address.isNotBlank(), onClick = { onSave(result(), false) }) { Text("Сохранить") }
        },
    )
}

@Composable
fun SettingsDialog(onDismiss: () -> Unit) {
    val prefs = TsSession.prefs
    val context = LocalContext.current
    var mode by remember { mutableStateOf(prefs.talkMode) }
    var threshold by remember { mutableFloatStateOf(prefs.vadThresholdDb) }
    var gain by remember { mutableFloatStateOf(prefs.playbackGain) }
    var speaker by remember { mutableStateOf(prefs.speakerphone) }
    var importing by remember { mutableStateOf(false) }

    fun apply() {
        prefs.talkMode = mode
        prefs.vadThresholdDb = threshold
        prefs.playbackGain = gain
        prefs.speakerphone = speaker
        TsSession.audio.talkMode = mode
        TsSession.audio.vadThresholdDb = threshold
        TsSession.audio.playbackGain = gain
    }

    AlertDialog(
        onDismissRequest = { apply(); onDismiss() },
        title = { Text("Настройки") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Режим передачи голоса", fontWeight = FontWeight.SemiBold)
                listOf(
                    TalkMode.PUSH_TO_TALK to "Нажми и говори",
                    TalkMode.VOICE_ACTIVATION to "Активация голосом",
                    TalkMode.CONTINUOUS to "Всегда передавать",
                ).forEach { (m, title) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(mode == m, { mode = m })
                        Text(title)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Порог активации: ${threshold.toInt()} дБ")
                Slider(threshold, { threshold = it }, valueRange = -70f..-5f)
                Text("Громкость собеседников: ${(gain * 100).toInt()}%")
                Slider(gain, { gain = it }, valueRange = 0.25f..4f)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Громкая связь по умолчанию", Modifier.weight(1f))
                    Switch(speaker, { speaker = it })
                }
                Spacer(Modifier.height(12.dp))
                Text("Личность (identity)", fontWeight = FontWeight.SemiBold)
                Text(
                    "Совместима с настольным TeamSpeak: экспортированную строку можно импортировать в клиент на ПК и наоборот.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row {
                    OutlinedButton(onClick = {
                        val text = prefs.identityExport
                        if (text == null) {
                            Toast.makeText(context, "Личность создаётся при первом подключении", Toast.LENGTH_SHORT).show()
                        } else {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("TeamSpeak identity", text))
                            Toast.makeText(context, "Скопировано", Toast.LENGTH_SHORT).show()
                        }
                    }) { Text("Экспорт") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { importing = true }) { Text("Импорт") }
                }
            }
        },
        confirmButton = { TextButton(onClick = { apply(); onDismiss() }) { Text("Готово") } },
    )

    if (importing) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { importing = false },
            title = { Text("Импорт личности") },
            text = {
                OutlinedTextField(text, { text = it }, label = { Text("Содержимое .ini файла identity") }, minLines = 4)
            },
            confirmButton = {
                TextButton(onClick = {
                    val ini = if (text.contains("[Identity]")) text else "[Identity]\nidentity=\"${text.trim().trim('"')}\"\n"
                    val old = prefs.identityExport
                    prefs.identityExport = ini
                    if (prefs.loadIdentity() == null) {
                        prefs.identityExport = old
                        Toast.makeText(context, "Не удалось прочитать личность", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Личность импортирована", Toast.LENGTH_SHORT).show()
                        importing = false
                    }
                }) { Text("Импортировать") }
            },
            dismissButton = { TextButton(onClick = { importing = false }) { Text("Отмена") } },
        )
    }
}

@Composable
fun ConnectingScreen(step: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(Modifier.size(48.dp))
            Spacer(Modifier.height(24.dp))
            Text(step)
            Spacer(Modifier.height(24.dp))
            OutlinedButton(onClick = { TsSession.disconnect() }) { Text("Отмена") }
        }
    }
}
