package com.v1svaldis.openspeak.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v1svaldis.openspeak.ts.ChatMessage
import com.v1svaldis.openspeak.ts.TsSession
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Discord-style text chat of the current channel (private and server messages are shown too). */
@Composable
fun ChatPage(messages: List<ChatMessage>, onBack: () -> Unit) {
    val channels by TsSession.channels.collectAsStateWithLifecycle()
    val clients by TsSession.clients.collectAsStateWithLifecycle()
    val ownId by TsSession.ownId.collectAsStateWithLifecycle()
    val channelName = channels[clients[ownId]?.channelId ?: 0]?.name ?: ""
    val listState = rememberLazyListState()
    var text by rememberSaveable { mutableStateOf("") }
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1) }

    Column(Modifier.fillMaxSize().background(Ds.Chat).statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", tint = Ds.TextMuted) }
            Icon(Icons.Default.Tag, null, tint = Ds.TextFaint, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(4.dp))
            Text(channelName, color = Ds.Text, fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Ds.Rail))

        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = PaddingValues(vertical = 12.dp)) {
            if (messages.isEmpty()) item {
                Column(Modifier.padding(16.dp)) {
                    Box(Modifier.size(64.dp).clip(CircleShape).background(Ds.Input), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Tag, null, tint = Ds.Text, modifier = Modifier.size(36.dp))
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("Добро пожаловать в #$channelName!", color = Ds.Text, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                    Text("Это начало переписки канала.", color = Ds.TextMuted)
                }
            }
            itemsIndexed(messages) { i, m ->
                val prev = messages.getOrNull(i - 1)
                // Consecutive messages of one author within 5 minutes are grouped under one header.
                val grouped = prev != null && prev.fromName == m.fromName && prev.targetMode == m.targetMode &&
                    m.time - prev.time < 5 * 60_000 && m.targetMode != 0
                if (m.targetMode == 0) SystemMessage(m, timeFmt) else UserMessage(m, grouped, timeFmt)
            }
        }

        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = text, onValueChange = { text = it }, maxLines = 5,
                placeholder = { Text("Написать в #$channelName", color = Ds.TextFaint) },
                shape = RoundedCornerShape(24.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Ds.Input, unfocusedContainerColor = Ds.Input,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = Ds.Blurple, focusedTextColor = Ds.Text, unfocusedTextColor = Ds.Text,
                ),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(if (text.isBlank()) Ds.Input else Ds.Blurple),
                contentAlignment = Alignment.Center,
            ) {
                IconButton(enabled = text.isNotBlank(), onClick = { TsSession.sendChannelMessage(text.trim()); text = "" }) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Отправить", tint = Color.White)
                }
            }
        }
    }
}

@Composable
private fun UserMessage(m: ChatMessage, grouped: Boolean, timeFmt: SimpleDateFormat) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = if (grouped) 2.dp else 14.dp)) {
        if (grouped) Spacer(Modifier.width(40.dp)) else Avatar(m.fromName.removePrefix("→ "), 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            if (!grouped) Row(verticalAlignment = Alignment.Bottom) {
                Text(m.fromName, color = if (m.own) Ds.Green else Ds.Text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Spacer(Modifier.width(8.dp))
                Text(timeFmt.format(Date(m.time)), color = Ds.TextFaint, fontSize = 12.sp)
                val scope = when (m.targetMode) {
                    1 -> "личное"
                    3 -> "сервер"
                    else -> null
                }
                if (scope != null) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        scope, color = Ds.TextMuted, fontSize = 11.sp,
                        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Ds.Input).padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                }
            }
            Text(m.text.stripBbCode(), color = Color(0xFFDBDEE1), fontSize = 15.sp)
        }
    }
}

@Composable
private fun SystemMessage(m: ChatMessage, timeFmt: SimpleDateFormat) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.width(40.dp), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Campaign, null, tint = Ds.Blurple, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                (if (m.fromName.isNotBlank()) "${m.fromName}: " else "") + m.text.stripBbCode(),
                color = Ds.TextMuted, fontSize = 14.sp,
            )
            Text(timeFmt.format(Date(m.time)), color = Ds.TextFaint, fontSize = 11.sp)
        }
    }
}

/** TeamSpeak messages use BBCode ([b], [url=...], [color=...]); show them as plain text. */
fun String.stripBbCode(): String =
    replace(Regex("\\[url=([^\\]]*)\\](.*?)\\[/url\\]", RegexOption.IGNORE_CASE), "$2 ($1)")
        .replace(Regex("\\[/?[a-z]+(=[^\\]]*)?\\]", RegexOption.IGNORE_CASE), "")
