package com.videoeditor.timeline

import android.net.Uri
import java.util.UUID

/**
 * Single clip on timeline. Trim is relative to original asset duration.
 * trimStartMs inclusive, trimEndMs exclusive. Valid: 0 <= trimStart < trimEnd <= durationMs
 */
data class TimelineClip(
    val id: String = UUID.randomUUID().toString(),
    val uri: Uri,
    val displayName: String,
    val durationMs: Long,
    val trimStartMs: Long = 0L,
    val trimEndMs: Long = durationMs
) {
    val trimmedDurationMs: Long get() = (trimEndMs - trimStartMs).coerceAtLeast(0L)

    fun withTrim(newStartMs: Long, newEndMs: Long): TimelineClip {
        val s = newStartMs.coerceIn(0L, durationMs - 1)
        val e = newEndMs.coerceIn(s + 1, durationMs)
        return copy(trimStartMs = s, trimEndMs = e)
    }

    fun split(atMsInTrim: Long): Pair<TimelineClip, TimelineClip>? {
        // at is absolute within original asset time, must be inside (trimStart, trimEnd)
        if (atMsInTrim <= trimStartMs || atMsInTrim >= trimEndMs) return null
        val a = copy(id = UUID.randomUUID().toString(), trimStartMs = trimStartMs, trimEndMs = atMsInTrim)
        val b = copy(id = UUID.randomUUID().toString(), trimStartMs = atMsInTrim, trimEndMs = trimEndMs)
        return a to b
    }
}

data class TimelineState(
    val clips: List<TimelineClip> = emptyList(),
    val selectedId: String? = null
) {
    val totalDurationMs: Long get() = clips.sumOf { it.trimmedDurationMs }
    val selectedClip: TimelineClip? get() = clips.find { it.id == selectedId }
    val selectedIndex: Int get() = clips.indexOfFirst { it.id == selectedId }.let { if (it == -1) 0 else it }

    fun copyWithSelection(newSelectedId: String?): TimelineState = copy(selectedId = newSelectedId)

    fun addClip(clip: TimelineClip, select: Boolean = true): TimelineState {
        val newClips = clips + clip
        return copy(clips = newClips, selectedId = if (select) clip.id else selectedId)
    }

    fun removeClip(id: String): TimelineState {
        val idx = clips.indexOfFirst { it.id == id }
        if (idx == -1) return this
        val newClips = clips.filterNot { it.id == id }
        val newSel = when {
            newClips.isEmpty() -> null
            selectedId == id -> newClips.getOrNull(idx)?.id ?: newClips.last().id
            else -> selectedId
        }
        return copy(clips = newClips, selectedId = newSel)
    }

    fun trimClip(id: String, newStartMs: Long, newEndMs: Long): TimelineState {
        val newClips = clips.map { if (it.id == id) it.withTrim(newStartMs, newEndMs) else it }
        return copy(clips = newClips)
    }

    /** Split clip at absolute asset time within its trim range */
    fun splitClip(id: String, atMsInAsset: Long): TimelineState {
        val idx = clips.indexOfFirst { it.id == id }
        if (idx == -1) return this
        val clip = clips[idx]
        val splitted = clip.split(atMsInAsset) ?: return this
        val newClips = clips.toMutableList().apply {
            removeAt(idx)
            add(idx, splitted.first)
            add(idx + 1, splitted.second)
        }
        return copy(clips = newClips, selectedId = splitted.second.id)
    }

    fun moveClip(fromIdx: Int, toIdx: Int): TimelineState {
        if (fromIdx !in clips.indices || toIdx !in clips.indices) return this
        if (fromIdx == toIdx) return this
        val mutable = clips.toMutableList()
        val item = mutable.removeAt(fromIdx)
        mutable.add(toIdx, item)
        return copy(clips = mutable)
    }

    /** Absolute timeline start Ms for clip at index */
    fun timelineStartMsFor(index: Int): Long {
        if (index !in clips.indices) return 0L
        return clips.take(index).sumOf { it.trimmedDurationMs }
    }

    fun timelineRangeFor(id: String): LongRange? {
        val idx = clips.indexOfFirst { it.id == id }
        if (idx == -1) return null
        val start = timelineStartMsFor(idx)
        val end = start + clips[idx].trimmedDurationMs
        return start..end
    }
}
