package com.videoeditor.ui

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.videoeditor.timeline.TimelineClip
import com.videoeditor.timeline.TimelineState

@Composable
fun TimelineView(
    state: TimelineState,
    onSelect: (String) -> Unit,
    onTrim: (String, Long, Long) -> Unit,
    onSplit: (String, Long) -> Unit,
    onRemove: (String) -> Unit,
    onMove: (Int, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier
            .background(Color(0xFF0F0F0F), RoundedCornerShape(12.dp))
            .padding(10.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Timeline • ${state.clips.size} clips • ${state.totalDurationMs / 1000}s", color = Color.White, style = MaterialTheme.typography.titleSmall)
            if (state.selectedClip != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilledTonalButton(onClick = {
                        val c = state.selectedClip!!
                        val mid = (c.trimStartMs + c.trimEndMs) / 2
                        onSplit(c.id, mid)
                    }, modifier = Modifier.height(32.dp)) {
                        Icon(Icons.Filled.ContentCut, null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp)); Text("Split mid", style = MaterialTheme.typography.labelSmall)
                    }
                    IconButton(onClick = { state.selectedClip?.let { onRemove(it.id) } }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.Delete, "remove", tint = Color(0xFFFF6B6B))
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        if (state.clips.isEmpty()) {
            Box(Modifier.fillMaxWidth().height(90.dp).background(Color(0xFF1A1A1A), RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                Text("Import videos to start timeline", color = Color.Gray)
            }
        } else {
            // Scaled strip: each clip width proportional to trimmed duration
            BoxWithConstraints(Modifier.fillMaxWidth().height(96.dp).background(Color(0xFF1E1E1E), RoundedCornerShape(8.dp)).padding(6.dp)) {
                val total = state.totalDurationMs.coerceAtLeast(1L).toFloat()
                val widthPx = constraints.maxWidth.toFloat()
                val pxPerMs = widthPx / total

                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    state.clips.forEachIndexed { idx, clip ->
                        val weight = (clip.trimmedDurationMs.toFloat() / total).coerceAtLeast(0.05f)
                        val isSelected = clip.id == state.selectedId
                        ClipStrip(
                            clip = clip,
                            widthPx = clip.trimmedDurationMs * pxPerMs,
                            isSelected = isSelected,
                            onSelect = { onSelect(clip.id) },
                            onTrimHandleDrag = { isLeft, deltaPx ->
                                val deltaMs = (deltaPx / pxPerMs).toLong()
                                if (isLeft) {
                                    val newStart = (clip.trimStartMs + deltaMs).coerceIn(0L, clip.trimEndMs - 500L)
                                    onTrim(clip.id, newStart, clip.trimEndMs)
                                } else {
                                    val newEnd = (clip.trimEndMs + deltaMs).coerceIn(clip.trimStartMs + 500L, clip.durationMs)
                                    onTrim(clip.id, clip.trimStartMs, newEnd)
                                }
                            },
                            modifier = Modifier.weight(weight).fillMaxHeight()
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // Selected clip trim controls
            state.selectedClip?.let { clip ->
                TrimControls(clip = clip, onTrim = { s, e -> onTrim(clip.id, s, e) }, onSplitAt = { at -> onSplit(clip.id, at) })
            }

            // Fallback horizontal scroll list with reorder hints
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(state.clips, key = { it.id }) { c ->
                    val sel = c.id == state.selectedId
                    Card(
                        onClick = { onSelect(c.id) },
                        colors = CardDefaults.cardColors(containerColor = if (sel) Color(0xFF2F6FED) else Color(0xFF252525)),
                        modifier = Modifier.width(180.dp)
                    ) {
                        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(c.displayName, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                            Text("${c.trimmedDurationMs / 1000f} s • trimmed ${c.trimStartMs / 1000f}-${c.trimEndMs / 1000f}s / ${c.durationMs / 1000f}s", color = Color.LightGray, style = MaterialTheme.typography.labelSmall)
                            LinearProgressIndicator(progress = c.trimmedDurationMs.toFloat() / c.durationMs.coerceAtLeast(1L), modifier = Modifier.fillMaxWidth().height(4.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ClipStrip(
    clip: TimelineClip,
    widthPx: Float,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onTrimHandleDrag: (isLeft: Boolean, deltaPx: Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (isSelected) Color(0xFF3A5BFF) else Color(0xFF3A3A3A))
            .clickable { onSelect() }
            .padding(horizontal = 18.dp)
    ) {
        // Handles
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .width(14.dp)
                .fillMaxHeight()
                .background(Color.White.copy(alpha = 0.85f), RoundedCornerShape(4.dp))
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        onTrimHandleDrag(true, dragAmount.x)
                    }
                }
        )
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .width(14.dp)
                .fillMaxHeight()
                .background(Color.White.copy(alpha = 0.85f), RoundedCornerShape(4.dp))
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        onTrimHandleDrag(false, dragAmount.x)
                    }
                }
        )
        Column(Modifier.align(Alignment.Center).padding(horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(clip.displayName.take(12), color = Color.White, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            Text("${(clip.trimmedDurationMs / 1000f).let { "%.1f".format(it) } }s", color = Color.White.copy(0.8f), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun TrimControls(clip: TimelineClip, onTrim: (Long, Long) -> Unit, onSplitAt: (Long) -> Unit) {
    var start by remember(clip.id, clip.trimStartMs) { mutableFloatStateOf(clip.trimStartMs.toFloat()) }
    var end by remember(clip.id, clip.trimEndMs) { mutableFloatStateOf(clip.trimEndMs.toFloat()) }
    // sync when clip changes externally
    LaunchedEffect(clip.trimStartMs, clip.trimEndMs) { start = clip.trimStartMs.toFloat(); end = clip.trimEndMs.toFloat() }

    Column(Modifier.fillMaxWidth().background(Color(0xFF1A1A1A), RoundedCornerShape(8.dp)).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Trim ${clip.displayName}: ${start.toLong() / 1000f}s → ${end.toLong() / 1000f}s (full ${clip.durationMs / 1000f}s)", color = Color.White, style = MaterialTheme.typography.labelMedium)
        // Visual bar
        BoxWithConstraints(Modifier.fillMaxWidth().height(28.dp).background(Color(0xFF2A2A2A), RoundedCornerShape(6.dp)).padding(4.dp)) {
            val w = constraints.maxWidth.toFloat()
            val ratio = w / clip.durationMs.toFloat().coerceAtLeast(1f)
            Canvas(Modifier.fillMaxSize()) {
                drawRoundRect(Color(0xFF444444), size = Size(w, size.height), cornerRadius = CornerRadius(6f, 6f))
                val sX = start * ratio
                val eX = end * ratio
                drawRoundRect(Color(0xFF00D1B2), topLeft = Offset(sX, 0f), size = Size((eX - sX).coerceAtLeast(4f), size.height), cornerRadius = CornerRadius(6f, 6f))
            }
        }
        Slider(value = start, onValueChange = { v ->
            start = v.coerceIn(0f, end - 500f)
            onTrim(start.toLong(), end.toLong())
        }, valueRange = 0f..clip.durationMs.toFloat(), modifier = Modifier.fillMaxWidth())
        Slider(value = end, onValueChange = { v ->
            end = v.coerceIn(start + 500f, clip.durationMs.toFloat())
            onTrim(start.toLong(), end.toLong())
        }, valueRange = 0f..clip.durationMs.toFloat(), modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { val at = ((start + end) / 2).toLong(); onSplitAt(at) }) { Text("Split here mid") }
            TextButton(onClick = { onTrim(0L, clip.durationMs) }) { Text("Reset trim") }
        }
    }
}
