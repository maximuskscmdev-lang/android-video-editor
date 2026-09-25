package com.videoeditor.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.videoeditor.timeline.ViewportRes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewportResSelector(
    selected: ViewportRes,
    onSelect: (ViewportRes) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }, modifier = modifier) {
        OutlinedTextField(
            value = "${selected.label} ${selected.width}x${selected.height}",
            onValueChange = {},
            readOnly = true,
            label = { Text("Viewport = Export") },
            supportingText = { Text("${selected.bitrate / 1_000_000} Mbps • aspect ${"%.2f".format(selected.aspect)}") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
            modifier = Modifier.menuAnchor().fillMaxWidth(),
            singleLine = true
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ViewportRes.entries.forEach { res ->
                DropdownMenuItem(
                    text = { Text("${res.label} ${res.width}x${res.height} ${res.bitrate / 1_000_000}Mbps") },
                    onClick = { onSelect(res); expanded = false },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                )
            }
        }
    }
}
