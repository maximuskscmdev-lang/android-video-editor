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
import com.videoeditor.timeline.ViewportRes
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
    var progress by remember { mutableIntStateOf(0) }
    var isExporting by remember { mutableStateOf(false) }

    val rustVer = remember { try { RustBridge.getVersion() } catch (_: Throwable) { "rust not loaded" } }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        val granted = results.values.all { it }
        if (!granted) Toast.makeText(ctx, "Permissions denied, import may fail", Toast.LENGTH_SHORT).show()
    }
    LaunchedEffect(Unit) {
        if (!Permissions.isGranted(ctx)) permLauncher.launch(Permissions.forApi.toTypedArray())
    }

    fun handleImport(uri: Uri) {
        try {
            UriResolver.takePersistablePermission(ctx, uri)
            val probe = AssetProbe.probe(ctx, uri)
            val name = UriResolver.queryName(ctx, uri) ?: "clip_${timeline.clips.size + 1}.mp4"
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

    // Map ViewportRes <-> ExportRes for dialog compatibility
    fun viewportToExport(v: ViewportRes): ExportRes = when (v) {
        ViewportRes.P720_LANDSCAPE -> ExportRes.P720
        ViewportRes.P1080_LANDSCAPE -> ExportRes.P1080
        ViewportRes.K4_LANDSCAPE -> ExportRes.K4
        ViewportRes.P720_PORTRAIT -> ExportRes.P720
        ViewportRes.P1080_PORTRAIT -> ExportRes.P1080
        ViewportRes.SQUARE_1080 -> ExportRes.P1080
    }
    fun exportToViewport(e: ExportRes): ViewportRes = when (e) {
        ExportRes.P720 -> ViewportRes.P720_LANDSCAPE
        ExportRes.P1080 -> ViewportRes.P1080_LANDSCAPE
        ExportRes.K4 -> ViewportRes.K4_LANDSCAPE
    }

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
            // Viewport resolution selector — single source for preview + export (option a)
            Text("Viewport = Export Resolution", style = MaterialTheme.typography.labelMedium)
            ViewportResChips(
                selected = timeline.viewportRes,
                onSelect = { res -> timeline = timeline.withViewportRes(res) },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "Selected ${timeline.viewportRes.label} ${timeline.viewportRes.width}x${timeline.viewportRes.height} — viewport aspect and export size linked",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary
            )

            // Live edited preview with per-clip transform (move/rotate/resize selected)
            TimelinePreview(
                timeline = timeline,
                onTransformChange = { id, newTf -> timeline = timeline.setClipTransform(id, newTf) },
                onResetTransform = { id -> timeline = timeline.resetClipTransform(id) },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                if (timeline.clips.isEmpty()) "Import to preview" else "Live edited: ${timeline.clips.size} clips stitched. Tap clip to select, pinch/rotate/drag viewport to move/scale/rotate selected clip.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = { pickSingle.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }) { Text("Add Video") }
                OutlinedButton(onClick = { pickMultiple.launch(arrayOf("video/*")) }) { Text("Add Multiple") }
                Button(
                    onClick = { showExport = true },
                    enabled = timeline.clips.isNotEmpty() && !isExporting
                ) { Text("Export ${timeline.viewportRes.label}") }
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

            // Transform readout for selected clip
            timeline.selectedClip?.let { sel ->
                val tf = sel.transform
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Text("Selected: ${sel.displayName}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { timeline = timeline.resetClipTransform(sel.id) }, enabled = tf != com.videoeditor.timeline.ClipTransform()) { Text("Reset pos") }
                }
                Text("x ${"%.2f".format(tf.offsetXFraction)} y ${"%.2f".format(tf.offsetYFraction)} scale ${"%.2f".format(tf.scale)} rot ${tf.rotationDeg.toInt()}°", style = MaterialTheme.typography.labelSmall)
            }

            if (isExporting) {
                LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                Text("Exporting $progress% to ${timeline.viewportRes.width}x${timeline.viewportRes.height}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    if (showExport) {
        val expSel = viewportToExport(timeline.viewportRes)
        ExportDialog(
            progress = progress,
            isExporting = isExporting,
            selected = expSel,
            onSelect = { e -> timeline = timeline.withViewportRes(exportToViewport(e)) },
            onExport = {
                scope.launch {
                    isExporting = true; progress = 0
                    val outFile = UriResolver.createOutputFile(ctx, "export_${System.currentTimeMillis()}_${timeline.viewportRes.width}x${timeline.viewportRes.height}.mp4")
                    val engine = VideoEngine(ctx)
                    // ExportConfig width/height ignored — engine uses timeline.viewportRes (option a)
                    val config = VideoEngine.ExportConfig(
                        outWidth = timeline.viewportRes.width, outHeight = timeline.viewportRes.height,
                        bitrate = timeline.viewportRes.bitrate, fps = 30
                    )
                    val result = engine.export(timeline, outFile.absolutePath, config) { p -> progress = p }
                    isExporting = false
                    if (result.isSuccess) {
                        Toast.makeText(ctx, "Exported ${outFile.name} ${timeline.viewportRes.label}", Toast.LENGTH_LONG).show()
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
