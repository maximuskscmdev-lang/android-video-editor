package com.videoeditor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * Simplified timeline: horizontal clips, handles for trim start/end, split button.
 * ExoPlayer preview scrub is handled in PreviewPlayer.
 */
@Composable
fun TimelineView(
    clips: List<ClipUi>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onTrimChange: (index: Int, startMs: Long, endMs: Long) -> Unit,
    onSplit: (index: Int, atMs: Long) -> Unit,
    onReorder: (from: Int, to: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.background(Color(0xFF121212)).padding(8.dp)) {
        Text("Timeline (${clips.size} clips)", color = Color.White, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            itemsIndexed(clips) { idx, clip ->
                ClipCard(
                    clip = clip,
                    isSelected = idx == selectedIndex,
                    onSelect = { onSelect(idx) },
                    onTrimChange = { s, e -> onTrimChange(idx, s, e) },
                    onSplit = { at -> onSplit(idx, at) }
                )
            }
        }
        if (clips.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            TrimHandles(
                startMs = clips[selectedIndex].trimStartMs,
                endMs = clips[selectedIndex].trimEndMs,
                durationMs = clips[selectedIndex].durationMs,
                onChange = { s, e -> onTrimChange(selectedIndex, s, e) }
            )
        }
    }
}

data class ClipUi(val name: String, val durationMs: Long, val trimStartMs: Long, val trimEndMs: Long)

@Composable
private fun ClipCard(clip: ClipUi, isSelected: Boolean, onSelect: () -> Unit, onTrimChange: (Long, Long) -> Unit, onSplit: (Long) -> Unit) {
    Card(
        onClick = onSelect,
        colors = CardDefaults.cardColors(containerColor = if (isSelected) Color(0xFF3F51B5) else Color(0xFF2A2A2A)),
        modifier = Modifier.width(160.dp).height(72.dp)
    ) {
        Column(Modifier.padding(8.dp)) {
            Text(clip.name, color = Color.White, maxLines = 1)
            Text("${(clip.trimEndMs - clip.trimStartMs) / 1000}s", color = Color.LightGray)
        }
    }
}

@Composable
private fun TrimHandles(startMs: Long, endMs: Long, durationMs: Long, onChange: (Long, Long) -> Unit) {
    var start by remember(startMs) { mutableStateOf(startMs) }
    var end by remember(endMs) { mutableStateOf(endMs) }
    Column {
        Text("Trim: ${start}ms .. ${end}ms / ${durationMs}ms", color = Color.White)
        Canvas(modifier = Modifier.fillMaxWidth().height(40.dp).pointerInput(Unit) {
            detectHorizontalDragGestures { _, _ -> }
        }) {
            val w = size.width
            val ratio = if (durationMs == 0L) 1f else w / durationMs
            drawRect(Color.Gray, Offset(0f, 12f), size.copy(height = 16f))
            val sX = start * ratio
            val eX = end * ratio
            drawRect(Color(0xFF00BCD4), Offset(sX, 12f), size.copy(width = (eX - sX).coerceAtLeast(4f), height = 16f))
            drawCircle(Color.White, 10f, Offset(sX, 20f))
            drawCircle(Color.White, 10f, Offset(eX, 20f))
        }
        Slider(value = start.toFloat(), onValueChange = { start = it.toLong().also { v -> onChange(v, end) } }, valueRange = 0f..durationMs.toFloat())
        Slider(value = end.toFloat(), onValueChange = { end = it.toLong().also { v -> onChange(start, v) } }, valueRange = 0f..durationMs.toFloat())
    }
}
