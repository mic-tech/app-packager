package com.mictech.apppackager.ui

import android.content.Context
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Icons are decoded off the main thread and kept around; the list scrolls past
 *  hundreds of them and re-decoding on every recomposition is visible jank. */
private val cache = LruCache<String, ImageBitmap>(300)

@Composable
fun AppIcon(packageName: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(cache[packageName], packageName) {
        if (value == null) value = loadIcon(context, packageName)
    }
    val image = bitmap
    if (image != null) {
        Image(bitmap = image, contentDescription = null, modifier = modifier)
    } else {
        Icon(
            Icons.Default.Android,
            contentDescription = null,
            modifier = modifier,
            tint = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

private suspend fun loadIcon(context: Context, packageName: String): ImageBitmap? =
    withContext(Dispatchers.IO) {
        runCatching {
            context.packageManager.getApplicationIcon(packageName)
                .toBitmap(width = 144, height = 144)
                .asImageBitmap()
                .also { cache.put(packageName, it) }
        }.getOrNull()
    }
