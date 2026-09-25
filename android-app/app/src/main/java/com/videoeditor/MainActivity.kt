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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.videoeditor.engine.VideoEngine
import com.videoeditor.timeline.TimelineClip
import com.videoeditor.timeline.TimelineState
import com.videoeditor.ui.*
import com.videoeditor.ui.theme.EditorColors
import com.videoeditor.ui.theme.EditorDimens
import com.videoeditor.ui.theme.EditorTheme
import com.videoeditor.ui.theme.SectionCard
import com.videoeditor.util.AssetProbe
import com.videoeditor.util.Permissions
import com.videoeditor.util.UriResolver
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { EditorTheme { App() } }
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
        if (results.values.any { !it }) Toast.makeText(ctx, "Permissions denied, import may fail", Toast.LENGTH_SHORT).show()
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
            timeline = timeline.addClip(
                TimelineClip(uri = uri, displayName = name, durationMs = durMs, trimStartMs = 0L, trimEndMs = durMs)
            )
            Toast.makeText(ctx, "Imported $name ${durMs / 1000f}s ${probe.width}x${probe.height}", Toast.LENGTH_SHORT).show()
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Video Editor • ${timeline.totalDurationMs / 1000f}s") },
                subtitle = { Text(rustVer, style = MaterialTheme.typography.labelSmall) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = EditorColors.Background,
                    titleContentColor = EditorColors.Text
                )
            )
        },
        bottomBar = {
            BottomAppBar(containerColor = EditorColors.Surface) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledTonalButton(
                        onClick = { pickSingle.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.VideoLibrary, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp)); Text("Add")
                    }
                    OutlinedButton(
                        onClick = { pickMultiple.launch(arrayOf("video/*")) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.Add, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp)); Text("Multi")
                    }
                    Button(
                        onClick = { showExport = true },
                        enabled = timeline.clips.isNotEmpty() && !isExporting,
                        modifier = Modifier.weight(1.2f)
                    ) {
                        Icon(Icons.Filled.FileDownload, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp)); Text("Export ${timeline.viewportRes.label}")
                    }
                }
            }
        },
        containerColor = EditorColors.Background
    ) { pad ->
        Column(
            modifier = Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(EditorDimens.CardPadding),
            verticalArrangement = Arrangement.spacedBy(EditorDimens.SectionGap)
        ) {
            // 1. Viewport — capped height, never overlaps
            TimelinePreview(
                timeline = timeline,
                onTransformChange = { id, newTf -> timeline = timeline.setClipTransform(id, newTf) },
                onSelect = { id -> timeline = timeline.copyWithSelection(id) },
                modifier = Modifier.fillMaxWidth()
            )

            // 2. Resolution — dropdown only, single line
            SectionCard {
                Text("Viewport = Export", color = EditorColors.Text, style = MaterialTheme.typography.titleSmall)
                ViewportResSelector(
                    selected = timeline.viewportRes,
                    onSelect = { res -> timeline = timeline.withViewportRes(res) },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // 3. Timeline strip — single LazyRow
            TimelineView(
                state = timeline,
                onSelect = { id -> timeline = timeline.copyWithSelection(id) },
                modifier = Modifier.fillMaxWidth()
            )

            // 4. Inspector tabs — Trim / Move / Split, one visible at a time
            val sel = timeline.selectedClip
            InspectorPanel(
                clip = sel,
                onTrim = { s, e -> sel?.let { timeline = timeline.trimClip(it.id, s, e) } },
                onTransform = { tf -> sel?.let { timeline = timeline.setClipTransform(it.id, tf) } },
                onResetTransform = { sel?.let { timeline = timeline.resetClipTransform(it.id) } },
                onSplitMid = {
                    sel?.let {
                        val mid = (it.trimStartMs + it.trimEndMs) / 2
                        timeline = timeline.splitClip(it.id, mid)
                    }
                },
                onSplitAt = { at -> sel?.let { timeline = timeline.splitClip(it.id, at) } },
                onResetTrim = { sel?.let { timeline = timeline.trimClip(it.id, 0L, it.durationMs) } },
                onRemove = { sel?.let { timeline = timeline.removeClip(it.id) } },
                modifier = Modifier.fillMaxWidth()
            )

            if (isExporting) {
                SectionCard {
                    Text(
                        "Exporting $progress% → ${timeline.viewportRes.width}x${timeline.viewportRes.height}",
                        color = EditorColors.Text,
                        style = MaterialTheme.typography.bodySmall
                    )
                    LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }

    if (showExport) {
        ExportDialog(
            progress = progress,
            isExporting = isExporting,
            viewportRes = timeline.viewportRes,
            onSelectViewport = { timeline = timeline.withViewportRes(it) },
            onExport = {
                scope.launch {
                    isExporting = true; progress = 0
                    val outFile = UriResolver.createOutputFile(
                        ctx,
                        "export_${System.currentTimeMillis()}_${timeline.viewportRes.width}x${timeline.viewportRes.height}.mp4"
                    )
                    val engine = VideoEngine(ctx)
                    val config = VideoEngine.ExportConfig(
                        outWidth = timeline.viewportRes.width,
                        outHeight = timeline.viewportRes.height,
                        bitrate = timeline.viewportRes.bitrate,
                        fps = 30
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
