package com.v1svaldis.openspeak.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.v1svaldis.openspeak.data.Bookmark
import com.v1svaldis.openspeak.data.TalkMode
import com.v1svaldis.openspeak.ts.TsSession

/** Full-screen page used for forms, like Discord's settings pages. */
@Composable
fun FullScreenPage(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onBack, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(Ds.Chat).statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", tint = Ds.TextMuted) }
                Text(title, style = MaterialTheme.typography.titleMedium, color = Ds.Text, modifier = Modifier.weight(1f))
                actions()
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) { content() }
        }
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = Ds.TextFaint, modifier = modifier.padding(top = 16.dp, bottom = 8.dp))
}

@Composable
fun DsField(
    label: String, value: String, onChange: (String) -> Unit, placeholder: String = "",
    password: Boolean = false, keyboard: KeyboardType = KeyboardType.Text,
) {
    SectionLabel(label)
    TextField(
        value = value, onValueChange = onChange, singleLine = true,
        placeholder = { Text(placeholder, color = Ds.TextFaint) },
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        shape = RoundedCornerShape(8.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Ds.Rail, unfocusedContainerColor = Ds.Rail,
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
            cursorColor = Ds.Blurple, focusedTextColor = Ds.Text, unfocusedTextColor = Ds.Text,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
fun BlurpleButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, color: Color = Ds.Blurple) {
    Button(
        onClick = onClick, enabled = enabled, shape = RoundedCornerShape(24.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.White),
        modifier = modifier.height(48.dp),
    ) { Text(text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp) }
}

@Composable
fun BookmarkPage(
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

    FullScreenPage(
        title = if (initial.id == 0L) "Добавить сервер" else "Настройки сервера",
        onBack = onDismiss,
        actions = {
            if (onDelete != null) IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Удалить", tint = Ds.Red) }
        },
    ) {
        Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
            Avatar(label.ifBlank { address.ifBlank { "?" } }, 72.dp)
        }
        DsField("Адрес сервера", address, { address = it }, "example.com или 1.2.3.4:9987", keyboard = KeyboardType.Uri)
        DsField("Ник", nickname, { nickname = it }, "Android")
        DsField("Название", label, { label = it }, "Как показывать сервер в списке")
        DsField("Пароль сервера", serverPassword, { serverPassword = it }, password = true)
        DsField("Канал при входе", channel, { channel = it }, "Необязательно")
        DsField("Пароль канала", channelPassword, { channelPassword = it }, password = true)
        DsField("Ключ привилегий", token, { token = it }, "Необязательно")
        Spacer(Modifier.height(24.dp))
        BlurpleButton("Подключиться", { onSave(result(), true) }, Modifier.fillMaxWidth(), enabled = address.isNotBlank())
        Spacer(Modifier.height(8.dp))
        TextButton(
            onClick = { onSave(result(), false) }, enabled = address.isNotBlank(), modifier = Modifier.fillMaxWidth(),
        ) { Text("Сохранить", color = Ds.TextMuted) }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun SettingsPage(onDismiss: () -> Unit) {
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

    val sliderColors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Ds.Blurple, inactiveTrackColor = Ds.Input)

    FullScreenPage(title = "Голос и видео", onBack = { apply(); onDismiss() }) {
        SectionLabel("Режим ввода")
        Column(Modifier.clip(RoundedCornerShape(8.dp)).background(Ds.Panel)) {
            listOf(
                TalkMode.VOICE_ACTIVATION to "Активация по голосу",
                TalkMode.PUSH_TO_TALK to "Режим рации (нажми и говори)",
                TalkMode.CONTINUOUS to "Всегда передавать",
            ).forEach { (m, title) ->
                Row(
                    Modifier.fillMaxWidth().clickable { mode = m }.padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(title, color = Ds.Text, modifier = Modifier.weight(1f))
                    RadioButton(mode == m, { mode = m }, colors = RadioButtonDefaults.colors(selectedColor = Ds.Blurple))
                }
            }
        }
        SectionLabel("Чувствительность микрофона: ${threshold.toInt()} дБ")
        Slider(threshold, { threshold = it }, valueRange = -70f..-5f, colors = sliderColors)
        SectionLabel("Громкость собеседников: ${(gain * 100).toInt()}%")
        Slider(gain, { gain = it }, valueRange = 0.25f..4f, colors = sliderColors)
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(8.dp)).background(Ds.Panel).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Громкая связь по умолчанию", color = Ds.Text, modifier = Modifier.weight(1f))
            Switch(speaker, { speaker = it }, colors = SwitchDefaults.colors(checkedTrackColor = Ds.Green))
        }

        SectionLabel("Личность (identity)")
        Text(
            "Совместима с настольным TeamSpeak: экспортированную строку можно импортировать в клиент на ПК и наоборот.",
            color = Ds.TextMuted, fontSize = 13.sp,
        )
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BlurpleButton("Экспорт", {
                val text = prefs.identityExport
                if (text == null) {
                    Toast.makeText(context, "Личность создаётся при первом подключении", Toast.LENGTH_SHORT).show()
                } else {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("TeamSpeak identity", text))
                    Toast.makeText(context, "Скопировано", Toast.LENGTH_SHORT).show()
                }
            }, Modifier.weight(1f), color = Ds.Input)
            BlurpleButton("Импорт", { importing = true }, Modifier.weight(1f), color = Ds.Input)
        }
        Spacer(Modifier.height(24.dp))
    }

    if (importing) {
        var text by remember { mutableStateOf("") }
        FullScreenPage(title = "Импорт личности", onBack = { importing = false }) {
            DsField("Содержимое .ini файла identity", text, { text = it })
            Spacer(Modifier.height(16.dp))
            BlurpleButton("Импортировать", {
                if (!prefs.importIdentity(text)) {
                    Toast.makeText(context, "Не удалось прочитать личность", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Личность импортирована", Toast.LENGTH_SHORT).show()
                    importing = false
                }
            }, Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun ConnectingScreen(step: String) {
    Box(Modifier.fillMaxSize().background(Ds.Chat), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            val title = TsSession.currentBookmark?.title ?: ""
            Avatar(title.ifBlank { "?" }, 80.dp)
            Spacer(Modifier.height(20.dp))
            Text(title, style = MaterialTheme.typography.titleLarge, color = Ds.Text)
            Spacer(Modifier.height(20.dp))
            CircularProgressIndicator(Modifier.size(32.dp), color = Ds.Blurple, strokeWidth = 3.dp)
            Spacer(Modifier.height(16.dp))
            Text(step, color = Ds.TextMuted, fontSize = 14.sp)
            Spacer(Modifier.height(32.dp))
            BlurpleButton("Отмена", { TsSession.disconnect() }, Modifier.width(200.dp), color = Ds.Input)
        }
    }
}
