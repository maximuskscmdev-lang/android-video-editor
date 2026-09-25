package com.videoeditor.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

object EditorColors {
    val Background = Color(0xFF101114)
    val Surface = Color(0xFF1A1D21)
    val Surface2 = Color(0xFF23272E)
    val Surface3 = Color(0xFF2A2F37)
    val Primary = Color(0xFF00D1B2)
    val Accent = Color(0xFF3A5BFF)
    val Danger = Color(0xFFFF6B6B)
    val Text = Color(0xFFFFFFFF)
    val Sub = Color(0xFFB0B6BE)
    val Muted = Color(0xFF6B7280)
    val Handle = Color(0xFFFFFFFF)
}

object EditorDimens {
    val Radius = 10.dp
    val CardPadding = 10.dp
    val SectionGap = 12.dp
    val InnerGap = 8.dp
    val ViewportMaxHeight = 320.dp
    val StripHeight = 88.dp
}

@Composable
fun EditorTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = EditorColors.Background,
            surface = EditorColors.Surface,
            surfaceVariant = EditorColors.Surface2,
            primary = EditorColors.Primary,
            secondary = EditorColors.Accent,
            onBackground = EditorColors.Text,
            onSurface = EditorColors.Text
        ),
        content = content
    )
}

@Composable
fun SectionCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(EditorDimens.Radius),
        colors = CardDefaults.elevatedCardColors(containerColor = EditorColors.Surface),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(EditorDimens.CardPadding),
            verticalArrangement = Arrangement.spacedBy(EditorDimens.InnerGap),
            content = content
        )
    }
}

@Composable
fun SectionTitle(text: String, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, color = EditorColors.Text, style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically, content = trailing)
    }
}

@Composable
fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(72.dp)
            .background(EditorColors.Surface2, RoundedCornerShape(EditorDimens.Radius)),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = EditorColors.Muted, style = MaterialTheme.typography.bodySmall)
    }
}
