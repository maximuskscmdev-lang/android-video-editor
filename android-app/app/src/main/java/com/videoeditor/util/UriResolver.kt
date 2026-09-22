package com.videoeditor.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream

/**
 * API 29+ scoped storage: content:// URIs from PickVisualMedia must be copied
 * to app cache for Rust/MediaExtractor path access.
 */
object UriResolver {

    fun copyToCache(context: Context, uri: Uri, hintName: String? = null): File {
        val name = hintName ?: queryName(context, uri) ?: "input_${System.currentTimeMillis()}.mp4"
        val out = File(context.cacheDir, name)
        context.contentResolver.openInputStream(uri)?.use { ins ->
            FileOutputStream(out).use { outs -> ins.copyTo(outs) }
        } ?: error("Cannot open $uri")
        return out
    }

    fun queryName(context: Context, uri: Uri): String? {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return c.getString(idx)
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    /** For MediaStore export: insert into Movies/VideoEditor */
    fun createOutputFile(context: Context, fileName: String): File {
        val dir = File(context.getExternalFilesDir(null), "VideoEditor").apply { mkdirs() }
        // Also visible in MediaStore via getExternalFilesDir then MediaScanner
        return File(dir, fileName)
    }
}
