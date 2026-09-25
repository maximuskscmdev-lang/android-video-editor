package com.videoeditor.ui

import android.net.Uri
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.compose.ui.platform.LocalContext
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
 * Live edited preview: concatenates trimmed clips via MediaItem ClippingConfiguration.
 * Viewport reflects trimming/clipping instantly — not the original file.
 */
@Composable
fun TimelinePreview(timeline: TimelineState, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            playWhenReady = false
            // Enable clipping and concat handling
        }
    }

    // Rebuild playlist whenever timeline clips/trim changes
    LaunchedEffect(timeline.clips) {
        if (timeline.clips.isEmpty()) {
            player.clearMediaItems()
            return@LaunchedEffect
        }
        val mediaItems = timeline.clips.map { clip ->
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
        // Preserve position if possible: keep current timeline position
        val wasPlaying = player.isPlaying
        val currentPos = player.currentPosition
        player.setMediaItems(mediaItems)
        player.prepare()
        // Seek to 0 or keep position clamped to new total duration
        val newTotal = timeline.totalDurationMs
        val seekTo = currentPos.coerceIn(0L, newTotal.coerceAtLeast(1L) - 1)
        if (mediaItems.isNotEmpty()) player.seekTo(seekTo)
        player.playWhenReady = wasPlaying
    }

    DisposableEffect(Unit) { onDispose { player.release() } }

    AndroidView(factory = { ctx ->
        PlayerView(ctx).apply {
            this.player = player
            useController = true
        }
    }, modifier = modifier, update = { it.player = player })
}
