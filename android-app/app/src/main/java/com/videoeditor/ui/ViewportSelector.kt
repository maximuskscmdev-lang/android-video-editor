package com.videoeditor.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
            value = selected.label,
            onValueChange = {},
            readOnly = true,
            label = { Text("Viewport = Export") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ViewportRes.entries.forEach { res ->
                DropdownMenuItem(
                    text = { Text("${res.label} ${res.width}x${res.height} ${res.bitrate/1_000_000}Mbps") },
                    onClick = { onSelect(res); expanded = false },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                )
            }
        }
    }
}

@Composable
fun ViewportResChips(
    selected: ViewportRes,
    onSelect: (ViewportRes) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ViewportRes.entries.forEach { res ->
            FilterChip(
                selected = selected == res,
                onClick = { onSelect(res) },
                label = { Text(res.label, style = MaterialTheme.typography.labelSmall) }
            )
        }
    }
}
