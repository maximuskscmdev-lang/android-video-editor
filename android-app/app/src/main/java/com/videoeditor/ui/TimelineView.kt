package com.videoeditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.videoeditor.timeline.TimelineState
import com.videoeditor.ui.theme.EditorColors
import com.videoeditor.ui.theme.EditorDimens
import com.videoeditor.ui.theme.EmptyHint
import com.videoeditor.ui.theme.SectionCard
import com.videoeditor.ui.theme.SectionTitle

/**
 * Single-strip timeline — unified editor theme.
 * No weights, no duplicate lists, no embedded trim sliders (those live in InspectorPanel tabs).
 */
@Composable
fun TimelineView(
    state: TimelineState,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    SectionCard(modifier = modifier) {
        SectionTitle("Timeline • ${state.clips.size} clips • ${state.totalDurationMs / 1000}s")

        if (state.clips.isEmpty()) {
            EmptyHint("Import videos to start timeline")
        } else {
            val total = state.totalDurationMs.coerceAtLeast(1L).toFloat()
            LazyRow(
                modifier = Modifier.fillMaxWidth().height(EditorDimens.StripHeight),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(2.dp)
            ) {
                items(state.clips, key = { it.id }) { clip ->
                    val frac = (clip.trimmedDurationMs / total).coerceIn(0.08f, 1f)
                    val cardWidth = (120 + frac * 140).dp
                    val isSelected = clip.id == state.selectedId
                    Column(
                        modifier = Modifier
                            .width(cardWidth)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) EditorColors.Accent else EditorColors.Surface3)
                            .clickable { onSelect(clip.id) }
                            .padding(8.dp),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            clip.displayName,
                            color = EditorColors.Text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelMedium
                        )
                        Text(
                            "${"%.1f".format(clip.trimmedDurationMs / 1000f)}s",
                            color = EditorColors.Text.copy(alpha = 0.85f),
                            style = MaterialTheme.typography.labelSmall
                        )
                        LinearProgressIndicator(
                            progress = { clip.trimmedDurationMs.toFloat() / clip.durationMs.coerceAtLeast(1L) },
                            modifier = Modifier.fillMaxWidth().height(4.dp),
                            color = if (isSelected) EditorColors.Handle else EditorColors.Primary,
                            trackColor = EditorColors.Surface2
                        )
                    }
                }
            }
            Text(
                "Tap a clip to edit below • order = export order",
                color = EditorColors.Sub,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}
