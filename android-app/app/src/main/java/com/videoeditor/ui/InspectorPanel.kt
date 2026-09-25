package com.videoeditor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import com.videoeditor.timeline.ClipTransform
import com.videoeditor.timeline.TimelineClip
import com.videoeditor.ui.theme.EditorColors
import com.videoeditor.ui.theme.EmptyHint
import com.videoeditor.ui.theme.SectionCard
import com.videoeditor.ui.theme.SectionTitle

private enum class InspectorTab(val label: String) { Trim("Trim"), Move("Move"), Split("Split") }

/**
 * Tabbed inspector (option 1) — one tab visible at a time, no stacking overlap.
 */
@Composable
fun InspectorPanel(
    clip: TimelineClip?,
    onTrim: (Long, Long) -> Unit,
    onTransform: (ClipTransform) -> Unit,
    onResetTransform: () -> Unit,
    onSplitMid: () -> Unit,
    onSplitAt: (Long) -> Unit,
    onResetTrim: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    SectionCard(modifier = modifier) {
        if (clip == null) {
            SectionTitle("Inspector")
            EmptyHint("Select a clip to trim, move or split")
            return@SectionCard
        }

        var tab by remember(clip.id) { mutableStateOf(InspectorTab.Trim) }

        SectionTitle(
            text = "Inspector • ${clip.displayName.take(22)}",
            trailing = {
                IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.Delete, "remove", tint = EditorColors.Danger)
                }
            }
        )

        TabRow(
            selectedTabIndex = tab.ordinal,
            containerColor = EditorColors.Surface2,
            contentColor = EditorColors.Text
        ) {
            InspectorTab.entries.forEach { t ->
                Tab(
                    selected = tab == t,
                    onClick = { tab = t },
                    text = { Text(t.label, style = MaterialTheme.typography.labelMedium) }
                )
            }
        }

        when (tab) {
            InspectorTab.Trim -> TrimTab(clip, onTrim, onResetTrim)
            InspectorTab.Move -> MoveTab(clip, onTransform, onResetTransform)
            InspectorTab.Split -> SplitTab(clip, onSplitMid, onSplitAt)
        }
    }
}

@Composable
private fun TrimTab(clip: TimelineClip, onTrim: (Long, Long) -> Unit, onResetTrim: () -> Unit) {
    var start by remember(clip.id, clip.trimStartMs) { mutableFloatStateOf(clip.trimStartMs.toFloat()) }
    var end by remember(clip.id, clip.trimEndMs) { mutableFloatStateOf(clip.trimEndMs.toFloat()) }
    LaunchedEffect(clip.trimStartMs, clip.trimEndMs) {
        start = clip.trimStartMs.toFloat()
        end = clip.trimEndMs.toFloat()
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "${"%.1f".format(start / 1000f)}s → ${"%.1f".format(end / 1000f)}s  (full ${"%.1f".format(clip.durationMs / 1000f)}s)",
            color = EditorColors.Text,
            style = MaterialTheme.typography.labelMedium
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
                .background(EditorColors.Surface2, RoundedCornerShape(6.dp))
                .padding(4.dp)
        ) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val w = constraints.maxWidth.toFloat()
                val ratio = w / clip.durationMs.toFloat().coerceAtLeast(1f)
                Canvas(Modifier.fillMaxSize()) {
                    drawRoundRect(EditorColors.Surface3, size = Size(w, size.height), cornerRadius = CornerRadius(6f, 6f))
                    val sX = start * ratio
                    val eX = end * ratio
                    drawRoundRect(
                        EditorColors.Primary,
                        topLeft = Offset(sX, 0f),
                        size = Size((eX - sX).coerceAtLeast(4f), size.height),
                        cornerRadius = CornerRadius(6f, 6f)
                    )
                }
            }
        }
        Text("Start", color = EditorColors.Sub, style = MaterialTheme.typography.labelSmall)
        Slider(
            value = start,
            onValueChange = { v -> start = v.coerceIn(0f, end - 500f); onTrim(start.toLong(), end.toLong()) },
            valueRange = 0f..clip.durationMs.toFloat(),
            modifier = Modifier.fillMaxWidth()
        )
        Text("End", color = EditorColors.Sub, style = MaterialTheme.typography.labelSmall)
        Slider(
            value = end,
            onValueChange = { v -> end = v.coerceIn(start + 500f, clip.durationMs.toFloat()); onTrim(start.toLong(), end.toLong()) },
            valueRange = 0f..clip.durationMs.toFloat(),
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onResetTrim) { Text("Reset trim") }
        }
    }
}

@Composable
private fun MoveTab(clip: TimelineClip, onTransform: (ClipTransform) -> Unit, onResetTransform: () -> Unit) {
    val tf = clip.transform
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "Drag viewport to move • pinch to resize • twist to rotate",
            color = EditorColors.Sub,
            style = MaterialTheme.typography.labelSmall
        )
        TransformSlider("Scale", tf.scale, 0.25f..3.5f) { onTransform(tf.copy(scale = it)) }
        TransformSlider("Rotate°", tf.rotationDeg, -180f..180f) { onTransform(tf.copy(rotationDeg = it)) }
        TransformSlider("Offset X", tf.offsetXFraction, -0.5f..0.5f) { onTransform(tf.copy(offsetXFraction = it)) }
        TransformSlider("Offset Y", tf.offsetYFraction, -0.5f..0.5f) { onTransform(tf.copy(offsetYFraction = it)) }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "x ${"%.2f".format(tf.offsetXFraction)}  y ${"%.2f".format(tf.offsetYFraction)}  s ${"%.2f".format(tf.scale)}  r ${tf.rotationDeg.toInt()}°",
                color = EditorColors.Sub,
                style = MaterialTheme.typography.labelSmall
            )
            OutlinedButton(onClick = onResetTransform, enabled = tf != ClipTransform()) {
                Icon(Icons.Filled.Refresh, null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp)); Text("Reset")
            }
        }
    }
}

@Composable
private fun TransformSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column {
        Text("$label: ${"%.2f".format(value)}", color = EditorColors.Text, style = MaterialTheme.typography.labelSmall)
        Slider(value = value, onValueChange = onChange, valueRange = range, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun SplitTab(clip: TimelineClip, onSplitMid: () -> Unit, onSplitAt: (Long) -> Unit) {
    var pos by remember(clip.id, clip.trimStartMs, clip.trimEndMs) {
        mutableFloatStateOf(((clip.trimStartMs + clip.trimEndMs) / 2).toFloat())
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Cut ${clip.displayName.take(22)} into two clips at playhead",
            color = EditorColors.Sub,
            style = MaterialTheme.typography.labelSmall
        )
        Slider(
            value = pos,
            onValueChange = { pos = it.coerceIn(clip.trimStartMs.toFloat() + 1, clip.trimEndMs.toFloat() - 1) },
            valueRange = clip.trimStartMs.toFloat()..clip.trimEndMs.toFloat().coerceAtLeast(clip.trimStartMs.toFloat() + 2),
            modifier = Modifier.fillMaxWidth()
        )
        Text("Cut at ${"%.1f".format(pos / 1000f)}s", color = EditorColors.Text, style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSplitMid) {
                Icon(Icons.Filled.ContentCut, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp)); Text("Split at middle")
            }
            OutlinedButton(onClick = { onSplitAt(pos.toLong()) }) { Text("Split here") }
        }
    }
}
