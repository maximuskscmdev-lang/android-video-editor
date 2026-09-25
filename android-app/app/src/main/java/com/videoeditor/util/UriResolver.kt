package com.videoeditor.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream

object UriResolver {

    fun copyToCache(context: Context, uri: Uri, hintName: String? = null): File {
        val name = hintName ?: queryName(context, uri) ?: "input_${System.currentTimeMillis()}.mp4"
        // sanitize
        val safe = name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val out = File(context.cacheDir, safe)
        // if same uri was already copied and file exists, overwrite
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

    fun takePersistablePermission(context: Context, uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) { }
    }

    fun createOutputFile(context: Context, fileName: String): File {
        val dir = File(context.getExternalFilesDir(null), "VideoEditor").apply { mkdirs() }
        return File(dir, fileName)
    }

    fun cacheFileForUri(context: Context, uri: Uri): File? {
        val name = queryName(context, uri) ?: return null
        return File(context.cacheDir, name.replace(Regex("[^a-zA-Z0-9._-]"), "_")).takeIf { it.exists() }
    }
}
