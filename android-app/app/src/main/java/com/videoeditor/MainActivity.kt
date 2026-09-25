package com.videoeditor

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.videoeditor.engine.VideoEngine
import com.videoeditor.timeline.TimelineClip
import com.videoeditor.timeline.TimelineState
import com.videoeditor.ui.*
import com.videoeditor.util.AssetProbe
import com.videoeditor.util.Permissions
import com.videoeditor.util.UriResolver
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var timeline by remember { mutableStateOf(TimelineState()) }
    var showExport by remember { mutableStateOf(false) }
    var exportRes by remember { mutableStateOf(ExportRes.P1080) }
    var progress by remember { mutableIntStateOf(0) }
    var isExporting by remember { mutableStateOf(false) }

    val rustVer = remember { try { RustBridge.getVersion() } catch (_: Throwable) { "rust not loaded" } }

    // Permissions launcher
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        val granted = results.values.all { it }
        if (!granted) Toast.makeText(ctx, "Permissions denied, import may fail on older Android", Toast.LENGTH_SHORT).show()
    }
    LaunchedEffect(Unit) {
        if (!Permissions.isGranted(ctx)) permLauncher.launch(Permissions.forApi.toTypedArray())
    }

    fun handleImport(uri: Uri) {
        try {
            UriResolver.takePersistablePermission(ctx, uri)
            val probe = AssetProbe.probe(ctx, uri)
            val name = UriResolver.queryName(ctx, uri) ?: "clip_${timeline.clips.size + 1}.mp4"
            // duration from probe, fallback 10s
            val durMs = probe.durationMs.coerceAtLeast(1000L)
            val clip = TimelineClip(
                uri = uri,
                displayName = name,
                durationMs = durMs,
                trimStartMs = 0L,
                trimEndMs = durMs
            )
            timeline = timeline.addClip(clip)
            Toast.makeText(ctx, "Imported $name ${durMs/1000f}s ${probe.width}x${probe.height}", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(ctx, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    val pickSingle = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) handleImport(uri)
    }
    val pickMultiple = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { handleImport(it) }
    }

    val selectedUri = timeline.selectedClip?.uri

    Scaffold(topBar = {
        TopAppBar(title = { Text("Video Editor — $rustVer • ${timeline.totalDurationMs/1000f}s") })
    }) { pad ->
        Column(
            Modifier
                .padding(pad)
                .padding(12.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Preview of selected clip (plays original uri, user can see trim range via timeline)
            PreviewPlayer(selectedUri, Modifier.fillMaxWidth().height(220.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = { pickSingle.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }) { Text("Add Video") }
                OutlinedButton(onClick = { pickMultiple.launch(arrayOf("video/*")) }) { Text("Add Multiple") }
                Button(
                    onClick = { showExport = true },
                    enabled = timeline.clips.isNotEmpty() && !isExporting
                ) { Text("Export") }
            }

            TimelineView(
                state = timeline,
                onSelect = { id -> timeline = timeline.copyWithSelection(id) },
                onTrim = { id, s, e -> timeline = timeline.trimClip(id, s, e) },
                onSplit = { id, at -> timeline = timeline.splitClip(id, at) },
                onRemove = { id -> timeline = timeline.removeClip(id) },
                onMove = { from, to -> timeline = timeline.moveClip(from, to) },
                modifier = Modifier.fillMaxWidth()
            )

            Text(
                "Import videos, then drag handles or use sliders to trim. Tap split mid to cut clip at midpoint. Export stitches trimmed clips in order.",
                style = MaterialTheme.typography.bodySmall
            )

            if (isExporting) {
                LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                Text("Exporting $progress%", style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    if (showExport) {
        ExportDialog(
            progress = progress,
            isExporting = isExporting,
            selected = exportRes,
            onSelect = { exportRes = it },
            onExport = {
                scope.launch {
                    isExporting = true; progress = 0
                    val outFile = UriResolver.createOutputFile(ctx, "export_${System.currentTimeMillis()}.mp4")
                    val engine = VideoEngine(ctx)
                    val config = VideoEngine.ExportConfig(
                        outWidth = exportRes.w, outHeight = exportRes.h, bitrate = exportRes.bitrate, fps = 30
                    )
                    val result = engine.export(timeline, outFile.absolutePath, config) { p -> progress = p }
                    isExporting = false
                    if (result.isSuccess) {
                        Toast.makeText(ctx, "Exported: ${result.getOrNull()}", Toast.LENGTH_LONG).show()
                        android.media.MediaScannerConnection.scanFile(ctx, arrayOf(outFile.absolutePath), arrayOf("video/mp4"), null)
                    } else {
                        Toast.makeText(ctx, "Export failed: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                    }
                }
            },
            onDismiss = { if (!isExporting) showExport = false }
        )
    }
}
