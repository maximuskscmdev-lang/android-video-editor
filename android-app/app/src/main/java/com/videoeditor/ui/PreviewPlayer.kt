package com.videoeditor.ui

import android.net.Uri
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
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
import com.videoeditor.ui.theme.EditorColors
import com.videoeditor.ui.theme.EditorDimens
import com.videoeditor.ui.theme.EmptyHint
import com.videoeditor.ui.theme.SectionCard

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
 * Live edited viewport — unified editor theme.
 * - Capped height so it never pushes content off-screen.
 * - Player layer is NOT transformed; only a selection outline is drawn.
 * - Gestures live on a transparent overlay above controller-free zone.
 */
@Composable
fun TimelinePreview(
    timeline: TimelineState,
    onTransformChange: (String, ClipTransform) -> Unit = { _, _ -> },
    onSelect: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val player = remember {
        ExoPlayer.Builder(context).build().apply { playWhenReady = false }
    }
    val selected = timeline.selectedClip
    val viewportRes = timeline.viewportRes

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

    SectionCard(modifier = modifier) {
        // Title row lives outside video — no overlay over controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (selected != null) "Preview • ${selected.displayName.take(20)}" else "Preview • stitched timeline",
                color = EditorColors.Text,
                style = MaterialTheme.typography.labelMedium
            )
            Text(
                "${viewportRes.label} ${viewportRes.width}x${viewportRes.height}",
                color = EditorColors.Sub,
                style = MaterialTheme.typography.labelSmall
            )
        }

        if (timeline.clips.isEmpty()) {
            EmptyHint("Import videos to start preview")
        } else {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = EditorDimens.ViewportMaxHeight)
                    .aspectRatio(viewportRes.aspect, matchHeightConstraintsFirst = false)
                    .clip(RoundedCornerShape(EditorDimens.Radius))
                    .background(Color.Black)
            ) {
                val viewportW = constraints.maxWidth.toFloat().coerceAtLeast(1f)
                val viewportH = constraints.maxHeight.toFloat().coerceAtLeast(1f)
                val transform = selected?.transform ?: ClipTransform()

                // 1. Video layer — no graphicsLayer, no gesture fight
                AndroidView(factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = player
                        useController = true
                    }
                }, modifier = Modifier.fillMaxSize(), update = { it.player = player })

                // 2. Transparent gesture layer (above video, below controller handled by PlayerView)
                if (selected != null) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .pointerInput(selected.id, viewportW, viewportH) {
                                detectTransformGestures { _, pan, zoom, rotation ->
                                    val cur = selected.transform
                                    val dxFrac = pan.x / viewportW
                                    val dyFrac = pan.y / viewportH
                                    val newScale = (cur.scale * zoom).coerceIn(0.25f, 3.5f)
                                    val newRot = cur.rotationDeg + rotation
                                    val newOffX = (cur.offsetXFraction + dxFrac).coerceIn(-0.5f, 0.5f)
                                    val newOffY = (cur.offsetYFraction + dyFrac).coerceIn(-0.5f, 0.5f)
                                    onTransformChange(
                                        selected.id,
                                        cur.copy(
                                            offsetXFraction = newOffX,
                                            offsetYFraction = newOffY,
                                            scale = newScale,
                                            rotationDeg = newRot
                                        )
                                    )
                                }
                            }
                    )

                    // 3. Selection outline mirrors transform without scaling video pixels
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val pad = 12f
                        val cx = size.width / 2 + transform.offsetXFraction * size.width
                        val cy = size.height / 2 + transform.offsetYFraction * size.height
                        val w = (size.width * transform.scale - pad * 2).coerceAtLeast(24f)
                        val h = (size.height * transform.scale - pad * 2).coerceAtLeast(24f)
                        drawRoundRect(
                            color = EditorColors.Primary,
                            topLeft = Offset(cx - w / 2, cy - h / 2),
                            size = Size(w, h),
                            cornerRadius = CornerRadius(12f, 12f),
                            style = Stroke(
                                width = 3f,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))
                            )
                        )
                        // corner dots
                        listOf(
                            Offset(cx - w / 2, cy - h / 2),
                            Offset(cx + w / 2, cy - h / 2),
                            Offset(cx - w / 2, cy + h / 2),
                            Offset(cx + w / 2, cy + h / 2)
                        ).forEach { drawCircle(EditorColors.Handle, 8f, it) }
                    }
                }
            }

            Text(
                if (timeline.clips.isEmpty()) "Import to preview"
                else "Live: ${timeline.clips.size} clips stitched • tap clip below to edit • drag viewport to move, pinch to resize, twist to rotate",
                color = EditorColors.Sub,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}
