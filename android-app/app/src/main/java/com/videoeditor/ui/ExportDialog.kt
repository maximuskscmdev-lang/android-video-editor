package com.videoeditor.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.videoeditor.timeline.ViewportRes
import com.videoeditor.ui.theme.EditorColors

enum class ExportRes(val w: Int, val h: Int, val bitrate: Int, val label: String) {
    P720(1280, 720, 5_000_000, "720p (5 Mbps)"),
    P1080(1920, 1080, 10_000_000, "1080p (10 Mbps)"),
    K4(3840, 2160, 35_000_000, "4K (35 Mbps)")
}

@Composable
fun ExportDialog(
    progress: Int,
    isExporting: Boolean,
    viewportRes: ViewportRes,
    onSelectViewport: (ViewportRes) -> Unit,
    onExport: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export mp4 • viewport linked") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Viewport = Export: ${viewportRes.label} ${viewportRes.width}x${viewportRes.height}",
                    style = MaterialTheme.typography.labelMedium,
                    color = EditorColors.Primary
                )
                ViewportRes.entries.forEach { r ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Row {
                            RadioButton(selected = viewportRes == r, onClick = { onSelectViewport(r) })
                            Text(
                                "${r.label} ${r.width}x${r.height} ${r.bitrate / 1_000_000}Mbps",
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }
                if (isExporting) {
                    LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                    Text("$progress %")
                }
            }
        },
        confirmButton = { Button(onClick = onExport, enabled = !isExporting) { Text("Export") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

// Back-compat overload for callers still using ExportRes (delegates to ViewportRes)
@Composable
fun ExportDialog(
    progress: Int,
    isExporting: Boolean,
    selected: ExportRes,
    onSelect: (ExportRes) -> Unit,
    onExport: () -> Unit,
    onDismiss: () -> Unit
) {
    fun exportToViewport(e: ExportRes): ViewportRes = when (e) {
        ExportRes.P720 -> ViewportRes.P720_LANDSCAPE
        ExportRes.P1080 -> ViewportRes.P1080_LANDSCAPE
        ExportRes.K4 -> ViewportRes.K4_LANDSCAPE
    }
    fun viewportToExport(v: ViewportRes): ExportRes = when (v) {
        ViewportRes.P720_LANDSCAPE, ViewportRes.P720_PORTRAIT -> ExportRes.P720
        ViewportRes.K4_LANDSCAPE -> ExportRes.K4
        else -> ExportRes.P1080
    }
    ExportDialog(
        progress = progress,
        isExporting = isExporting,
        viewportRes = exportToViewport(selected),
        onSelectViewport = { onSelect(viewportToExport(it)) },
        onExport = onExport,
        onDismiss = onDismiss
    )
}
