package com.v1svaldis.openspeak.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Palette modelled on Discord's dark theme. */
object Ds {
    val Rail = Color(0xFF1E1F22)
    val Panel = Color(0xFF2B2D31)
    val Chat = Color(0xFF313338)
    val Input = Color(0xFF383A40)
    val Selected = Color(0xFF404249)
    val Hover = Color(0xFF35373C)
    val Call = Color(0xFF111214)
    val Tile = Color(0xFF232428)
    val Blurple = Color(0xFF5865F2)
    val Green = Color(0xFF23A55A)
    val Red = Color(0xFFF23F43)
    val Yellow = Color(0xFFF0B232)
    val Text = Color(0xFFF2F3F5)
    val TextMuted = Color(0xFFB5BAC1)
    val TextFaint = Color(0xFF949BA4)
    val Divider = Color(0xFF3F4147)

    private val avatarColors = listOf(
        Color(0xFF5865F2), Color(0xFF757E8A), Color(0xFF3BA55C),
        Color(0xFFFAA61A), Color(0xFFED4245), Color(0xFFEB459E),
    )

    fun avatarColor(key: String): Color = avatarColors[Math.floorMod(key.hashCode(), avatarColors.size)]
}

val AppColors = darkColorScheme(
    primary = Ds.Blurple,
    onPrimary = Color.White,
    primaryContainer = Ds.Blurple,
    onPrimaryContainer = Color.White,
    secondary = Ds.Green,
    background = Ds.Chat,
    surface = Ds.Panel,
    surfaceVariant = Ds.Input,
    surfaceContainer = Ds.Panel,
    surfaceContainerHigh = Ds.Chat,
    surfaceContainerHighest = Ds.Input,
    onSurface = Ds.Text,
    onSurfaceVariant = Ds.TextMuted,
    outline = Ds.Divider,
    error = Ds.Red,
    errorContainer = Color(0xFF4A1F22),
    onErrorContainer = Color(0xFFFFDAD6),
)

val AppTypography = Typography(
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 15.sp),
    labelSmall = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = AppColors, typography = AppTypography, content = content)

/** Round avatar with the first letter of a name, like Discord's default avatars. */
@Composable
fun Avatar(name: String, size: Dp, modifier: Modifier = Modifier, talking: Boolean = false) {
    val ring = if (talking) Modifier.border(if (size > 40.dp) 3.dp else 2.dp, Ds.Green, CircleShape) else Modifier
    Box(modifier.size(size).then(ring).padding(if (talking) 3.dp else 0.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(size).clip(CircleShape).background(Ds.avatarColor(name)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                name.trim().firstOrNull()?.uppercase() ?: "?",
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = (size.value * 0.42f).sp,
            )
        }
    }
}

/** Red "LIVE" pill shown next to users who are streaming. */
@Composable
fun LiveBadge(modifier: Modifier = Modifier) {
    Box(
        modifier.clip(RoundedCornerShape(4.dp)).background(Ds.Red).padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Text("В ЭФИРЕ", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}
