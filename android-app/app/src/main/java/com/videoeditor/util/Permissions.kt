package com.videoeditor.util

import android.Manifest
import android.os.Build

/** Permissions for API 29+ */
object Permissions {
    val forApi: List<String> = when {
        Build.VERSION.SDK_INT >= 33 -> listOf(
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO,
            Manifest.permission.READ_MEDIA_IMAGES
        )
        Build.VERSION.SDK_INT >= 29 -> listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        else -> listOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    fun isGranted(context: android.content.Context): Boolean {
        return forApi.all { androidx.core.content.ContextCompat.checkSelfPermission(context, it) == android.content.pm.PackageManager.PERMISSION_GRANTED }
    }
}

// Usage in Compose:
// @OptIn(ExperimentalPermissionsApi::class)
// val state = rememberMultiplePermissionsState(Permissions.forApi)
// LaunchedEffect(Unit) { if (!state.allPermissionsGranted) state.launchMultiplePermissionRequest() }
