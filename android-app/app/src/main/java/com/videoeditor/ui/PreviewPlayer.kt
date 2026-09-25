package com.videoeditor.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.videoeditor.timeline.ClipTransform
import com.videoeditor.timeline.TimelineState

@Composable
fun PreviewPlayer(uri: Uri?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val player = remember {
        ExoPlayer.Builder(context).build().apply { playWhenReady = false }
    }
    DisposableEffect(uri) {
        if (uri != null) {
            player.setMediaItem(MediaItem.fromUri(uri))
            player.prepare()
            player.playWhenReady = false
        } else {
            player.clearMediaItems()
        }
        onDispose {}
    }
    DisposableEffect(Unit) { onDispose { player.release() } }

    AndroidView(factory = { ctx ->
        PlayerView(ctx).apply {
            this.player = player
            useController = true
        }
    }, modifier = modifier, update = { view ->
        view.player = player
    })
}

/**
 * Live edited viewport: respects viewportRes aspect and per-clip transform.
 * - Viewport box uses aspectRatio(viewportRes)
 * - If a clip is selected, isolates that clip's trimmed segment with live transform overlay (move/rotate/resize)
 * - Else shows stitched timeline (clipped concat)
 * Gestures update selected clip's ClipTransform and call onTransformChange.
 */
@Composable
fun TimelinePreview(
    timeline: TimelineState,
    onTransformChange: (String, ClipTransform) -> Unit = { _, _ -> },
    onResetTransform: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val player = remember {
        ExoPlayer.Builder(context).build().apply { playWhenReady = false }
    }
    val selected = timeline.selectedClip
    val viewportRes = timeline.viewportRes

    // Choose playlist: isolated selected clip for transform editing, else stitched timeline
    val isIsolated = selected != null
    LaunchedEffect(timeline.clips, selected?.id, selected?.trimStartMs, selected?.trimEndMs) {
        if (timeline.clips.isEmpty()) {
            player.clearMediaItems()
            return@LaunchedEffect
        }
        val items: List<MediaItem> = if (isIsolated && selected != null) {
            listOf(
                MediaItem.Builder()
                    .setUri(selected.uri)
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(selected.trimStartMs)
                            .setEndPositionMs(selected.trimEndMs)
                            .build()
                    )
                    .build()
            )
        } else {
            timeline.clips.map { clip ->
                MediaItem.Builder()
                    .setUri(clip.uri)
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(clip.trimStartMs)
                            .setEndPositionMs(clip.trimEndMs)
                            .build()
                    )
                    .build()
            }
        }
        val wasPlaying = player.isPlaying
        val pos = player.currentPosition
        player.setMediaItems(items)
        player.prepare()
        if (isIsolated) player.seekTo(0) else {
            val total = timeline.totalDurationMs
            player.seekTo(pos.coerceIn(0L, total.coerceAtLeast(1L) - 1))
        }
        player.playWhenReady = wasPlaying
    }

    DisposableEffect(Unit) { onDispose { player.release() } }

    // Viewport box with aspect from viewportRes (single source drives export)
    BoxWithConstraints(
        modifier
            .aspectRatio(viewportRes.aspect, matchHeightConstraintsFirst = false)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black)
    ) {
        val viewportW = constraints.maxWidth.toFloat()
        val viewportH = constraints.maxHeight.toFloat()

        // Transformable video layer
        val transform = selected?.transform ?: ClipTransform()
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // offset fractions relative to viewport size (0 centered)
                    translationX = transform.offsetXFraction * viewportW
                    translationY = transform.offsetYFraction * viewportH
                    scaleX = transform.scale
                    scaleY = transform.scale
                    rotationZ = transform.rotationDeg
                }
                .pointerInput(selected?.id) {
                    if (selected == null) return@pointerInput
                    detectTransformGestures { _, pan, zoom, rotation ->
                        val cur = selected.transform
                        // pan is in pixels, convert to fraction
                        val dxFrac = pan.x / viewportW
                        val dyFrac = pan.y / viewportH
                        val newScale = (cur.scale * zoom).coerceIn(0.25f, 3.5f)
                        val newRot = cur.rotationDeg + rotation
                        val newOffX = (cur.offsetXFraction + dxFrac).coerceIn(-0.5f, 0.5f)
                        val newOffY = (cur.offsetYFraction + dyFrac).coerceIn(-0.5f, 0.5f)
                        onTransformChange(selected.id, cur.copy(offsetXFraction = newOffX, offsetYFraction = newOffY, scale = newScale, rotationDeg = newRot))
                    }
                }
        ) {
            AndroidView(factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = true
                }
            }, modifier = Modifier.fillMaxSize(), update = { it.player = player })
        }

        // Overlay handles/labels when clip selected
        if (selected != null) {
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    "${selected.displayName} • scale ${"%.2f".format(transform.scale)} • rot ${transform.rotationDeg.toInt()}°",
                    color = Color.White, style = MaterialTheme.typography.labelSmall
                )
            }
            if (transform != ClipTransform()) {
                Box(Modifier.align(Alignment.TopEnd).padding(6.dp)) {
                    androidx.compose.material3.FilledTonalButton(
                        onClick = { onResetTransform(selected.id) },
                        modifier = Modifier.height(28.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp)
                    ) { Text("Reset", style = MaterialTheme.typography.labelSmall) }
                }
            }
        }

        // Viewport res badge
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .background(Color.White.copy(alpha = 0.85f), RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Text("${viewportRes.label} ${viewportRes.width}x${viewportRes.height}", style = MaterialTheme.typography.labelSmall, color = Color.Black)
        }

        if (timeline.clips.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Import to preview", color = Color.Gray)
            }
        }
    }
}
