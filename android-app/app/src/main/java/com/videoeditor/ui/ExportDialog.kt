package com.videoeditor.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

enum class ExportRes(val w: Int, val h: Int, val bitrate: Int, val label: String) {
    P720(1280, 720, 5_000_000, "720p (5 Mbps)"),
    P1080(1920, 1080, 10_000_000, "1080p (10 Mbps)"),
    K4(3840, 2160, 35_000_000, "4K (35 Mbps)")
}

@Composable
fun ExportDialog(
    progress: Int,
    isExporting: Boolean,
    selected: ExportRes,
    onSelect: (ExportRes) -> Unit,
    onExport: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export mp4") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ExportRes.entries.forEach { r ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Row {
                            RadioButton(selected = selected == r, onClick = { onSelect(r) })
                            Text(r.label, modifier = Modifier.padding(start = 8.dp))
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
