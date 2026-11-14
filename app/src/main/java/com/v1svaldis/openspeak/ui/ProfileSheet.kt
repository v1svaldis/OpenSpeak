package com.v1svaldis.openspeak.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v1svaldis.openspeak.ts.TsClient
import com.v1svaldis.openspeak.ts.TsSession

/** Discord-like user profile sheet: banner, avatar, and actions (watch stream, message, poke). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileSheet(c: TsClient, onDismiss: () -> Unit) {
    val ownId by TsSession.ownId.collectAsStateWithLifecycle()
    val streams by TsSession.streams.streams.collectAsStateWithLifecycle()
    val stream = streamOf(streams, c.id)
    var text by remember { mutableStateOf("") }
    val isMe = c.id == ownId

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Ds.Panel, dragHandle = null) {
        Column(Modifier.navigationBarsPadding()) {
            Box(Modifier.fillMaxWidth().height(90.dp).background(Ds.avatarColor(c.nickname).copy(alpha = 0.6f)))
            Box(Modifier.padding(start = 16.dp).offset(y = (-40).dp).size(88.dp).clip(CircleShape).background(Ds.Panel).padding(6.dp)) {
                Avatar(c.nickname, 76.dp)
            }
            Column(Modifier.padding(horizontal = 16.dp).offset(y = (-28).dp)) {
                Row {
                    Text(c.nickname, color = Ds.Text, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                    if (stream != null) {
                        Spacer(Modifier.width(8.dp))
                        LiveBadge(Modifier.padding(top = 6.dp))
                    }
                }
                val status = buildList {
                    if (c.platform.isNotBlank()) add(c.platform)
                    if (c.away) add("отошёл" + if (c.awayMessage.isNotBlank()) ": ${c.awayMessage}" else "")
                    if (c.inputMuted) add("микрофон выключен")
                    if (c.outputMuted) add("звук выключен")
                }
                if (status.isNotEmpty()) Text(status.joinToString(" · "), color = Ds.TextMuted, fontSize = 14.sp)
                Spacer(Modifier.height(16.dp))
                if (stream != null) {
                    BlurpleButton(stream.name.ifBlank { "Смотреть трансляцию" }.let { "Смотреть: $it" }, {
                        TsSession.streams.watch(stream)
                        onDismiss()
                    }, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(12.dp))
                }
                if (!isMe) {
                    Column(Modifier.clip(RoundedCornerShape(12.dp)).background(Ds.Rail).padding(horizontal = 12.dp, vertical = 4.dp)) {
                        DsField("Сообщение для @${c.nickname}", text, { text = it })
                        Row(Modifier.padding(vertical = 12.dp)) {
                            BlurpleButton("Написать", {
                                TsSession.sendPrivateMessage(c.id, text.trim())
                                onDismiss()
                            }, Modifier.weight(1f), enabled = text.isNotBlank())
                            Spacer(Modifier.width(8.dp))
                            BlurpleButton("Poke", {
                                TsSession.poke(c.id, text.trim())
                                onDismiss()
                            }, Modifier.weight(1f), color = Ds.Input)
                        }
                    }
                }
            }
        }
    }
}
