package openspeak.app.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import openspeak.app.R
import openspeak.app.data.Bookmark
import openspeak.app.ts.TsSession

/** One icon of the left server rail. */
data class RailItem(val key: Long, val title: String)

/** Discord's left column of round server icons. */
@Composable
fun ServerRail(
    items: List<RailItem>,
    selected: Long?,
    connected: Long?,
    onSelect: (Long) -> Unit,
    onAdd: () -> Unit,
    onSettings: () -> Unit,
) {
    Column(
        Modifier.width(72.dp).fillMaxHeight().background(Ds.Rail).statusBarsPadding().navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(12.dp))
        RailIcon(selected = false, onClick = onSettings, background = Color.Transparent) {
            Image(painterResource(R.drawable.ic_logo), "Open Speak — настройки", Modifier.size(48.dp))
        }
        Box(Modifier.padding(vertical = 8.dp).width(32.dp).height(2.dp).background(Ds.Divider))
        LazyColumn(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            items(items, key = { it.key }) { item ->
                RailIcon(selected = item.key == selected, onClick = { onSelect(item.key) }, background = Ds.avatarColor(item.title)) {
                    Text(
                        item.title.trim().take(2).uppercase().ifBlank { "?" },
                        color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
                    )
                    if (item.key == connected) {
                        // Green dot: we are connected to this server.
                        Box(
                            Modifier.align(Alignment.BottomEnd).offset(2.dp, 2.dp).size(16.dp).clip(CircleShape)
                                .background(Ds.Rail).padding(3.dp).clip(CircleShape).background(Ds.Green)
                        )
                    }
                }
            }
            item {
                RailIcon(selected = false, onClick = onAdd, background = Ds.Panel) {
                    Icon(Icons.Default.Add, "Добавить сервер", tint = Ds.Green)
                }
            }
        }
    }
}

@Composable
private fun RailIcon(selected: Boolean, onClick: () -> Unit, background: Color, content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
        // The white pill on the left edge marks the selected server.
        Box(
            Modifier.align(Alignment.CenterStart).width(4.dp).height(if (selected) 40.dp else 0.dp)
                .clip(RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp)).background(Color.White)
        )
        Box(
            Modifier.size(48.dp).clip(if (selected) RoundedCornerShape(16.dp) else CircleShape).background(background)
                .clickable(onClick = onClick).animateContentSize(),
            contentAlignment = Alignment.Center,
            content = content,
        )
    }
}

@Composable
fun HomeScreen(
    error: String?,
    linkBookmark: Bookmark?,
    onLinkConsumed: () -> Unit,
    onConnect: (Bookmark) -> Unit,
) {
    val prefs = TsSession.prefs
    var bookmarks by remember { mutableStateOf(prefs.bookmarks) }
    var selectedId by rememberSaveable { mutableLongStateOf(TsSession.currentBookmark?.id ?: prefs.bookmarks.firstOrNull()?.id ?: 0L) }
    var editing by remember { mutableStateOf<Bookmark?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    val selected = bookmarks.firstOrNull { it.id == selectedId } ?: bookmarks.firstOrNull()

    LaunchedEffect(linkBookmark) {
        if (linkBookmark != null) {
            editing = linkBookmark
            onLinkConsumed()
        }
    }

    Row(Modifier.fillMaxSize()) {
        ServerRail(
            items = bookmarks.map { RailItem(it.id, it.title) },
            selected = selected?.id, connected = null,
            onSelect = { selectedId = it }, onAdd = { editing = Bookmark(id = 0) }, onSettings = { showSettings = true },
        )
        Column(
            Modifier.fillMaxSize().clip(RoundedCornerShape(topStart = 16.dp)).background(Ds.Panel)
                .statusBarsPadding().navigationBarsPadding(),
        ) {
            if (error != null) ErrorBanner(error)
            if (selected == null) {
                Welcome(onAdd = { editing = Bookmark(id = 0) })
            } else {
                ServerCard(selected, onConnect = { onConnect(selected) }, onEdit = { editing = selected })
            }
        }
    }

    editing?.let { b ->
        BookmarkPage(
            initial = b,
            onDismiss = { editing = null },
            onSave = { saved, connect ->
                val list = bookmarks.toMutableList()
                val stored = if (saved.id == 0L) saved.copy(id = System.currentTimeMillis()) else saved
                val idx = list.indexOfFirst { it.id == stored.id }
                if (idx >= 0) list[idx] = stored else list += stored
                prefs.bookmarks = list
                bookmarks = list
                selectedId = stored.id
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

    if (showSettings) SettingsPage(onDismiss = { showSettings = false })
}

@Composable
private fun ErrorBanner(error: String) {
    Row(
        Modifier.fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF3A2224))
            .clickable { TsSession.acknowledge() }.padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.ErrorOutline, null, tint = Ds.Red)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Не удалось подключиться", color = Ds.Text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(error, color = Ds.TextMuted, fontSize = 13.sp)
        }
        Text("OK", color = Ds.Blurple, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun Welcome(onAdd: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(painterResource(R.drawable.ic_logo), null, Modifier.size(112.dp))
        Spacer(Modifier.height(24.dp))
        Text("Добро пожаловать в Open Speak", style = MaterialTheme.typography.titleLarge, color = Ds.Text, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "Открытый клиент для серверов TeamSpeak 6 (и 3). Добавьте сервер, чтобы общаться голосом, показывать свой экран и смотреть чужие трансляции.",
            color = Ds.TextMuted, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        BlurpleButton("Добавить сервер", onAdd, Modifier.fillMaxWidth())
    }
}

@Composable
private fun ServerCard(b: Bookmark, onConnect: () -> Unit, onEdit: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        // Banner in the server's colour with its icon overlapping the edge, as on a Discord server page.
        Box(Modifier.fillMaxWidth().height(120.dp).background(Ds.avatarColor(b.title).copy(alpha = 0.55f)))
        Column(Modifier.padding(horizontal = 16.dp).offset(y = (-40).dp)) {
            Box(Modifier.size(88.dp).clip(RoundedCornerShape(26.dp)).background(Ds.Panel).padding(6.dp)) {
                Box(
                    Modifier.fillMaxSize().clip(RoundedCornerShape(22.dp)).background(Ds.avatarColor(b.title)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(b.title.trim().take(2).uppercase(), color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(b.title, style = MaterialTheme.typography.titleLarge, color = Ds.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text(b.address, color = Ds.TextMuted, fontSize = 14.sp)
            Spacer(Modifier.height(16.dp))
            InfoRow(Icons.Default.Headset, "Ник", b.nickname.ifBlank { "Android" })
            if (b.defaultChannel.isNotBlank()) InfoRow(Icons.Default.Settings, "Канал при входе", b.defaultChannel)
            Spacer(Modifier.height(24.dp))
            BlurpleButton("Подключиться", onConnect, Modifier.fillMaxWidth(), color = Ds.Green)
            Spacer(Modifier.height(8.dp))
            BlurpleButton("Изменить", onEdit, Modifier.fillMaxWidth(), color = Ds.Input)
        }
    }
}

@Composable
private fun InfoRow(icon: ImageVector, label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Ds.TextFaint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, color = Ds.TextFaint, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(value, color = Ds.Text, fontSize = 14.sp)
    }
}
