package com.videoeditor.ui

import android.net.Uri
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.compose.ui.platform.LocalContext

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
