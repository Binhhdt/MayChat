package com.maychat.app.ui.chat

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.maychat.app.data.MediaCache
import com.maychat.app.data.attempt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Shows one chat picture on the whole screen. Pinch with two fingers to
// zoom, drag to move. "Lưu về máy" saves a copy into the phone's gallery.
@Composable
fun ImageViewer(path: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val bitmap by produceState(initialValue = MediaCache.cachedBitmap(path), path) {
        if (value == null) value = attempt { MediaCache.bitmap(path) }.getOrNull()
    }
    val loaded = bitmap

    var scale by remember(path) { mutableFloatStateOf(1f) }
    var offset by remember(path) { mutableStateOf(Offset.Zero) }
    var note by remember(path) { mutableStateOf<String?>(null) }
    var saving by remember(path) { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            if (loaded == null) {
                Text(
                    "Đang tải ảnh…",
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                Image(
                    bitmap = loaded.asImageBitmap(),
                    contentDescription = "Ảnh",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(path) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                val newScale = (scale * zoom).coerceIn(1f, 5f)
                                scale = newScale
                                // Moving is only useful while zoomed in.
                                offset = if (newScale <= 1f) Offset.Zero else offset + pan
                            }
                        }
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        },
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .background(Color.Black.copy(alpha = 0.45f))
                    .safeDrawingPadding()
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onClose) { Text("‹ Đóng", color = Color.White) }
                TextButton(
                    enabled = loaded != null && !saving,
                    onClick = {
                        saving = true
                        scope.launch {
                            val ok = attempt {
                                saveImageToGallery(context, MediaCache.bytes(path))
                            }.getOrDefault(false)
                            note = if (ok) {
                                "Đã lưu vào thư viện ảnh, thư mục MayChat."
                            } else {
                                "Không lưu được ảnh trên máy này."
                            }
                            saving = false
                        }
                    },
                ) { Text(if (saving) "Đang lưu…" else "Lưu về máy", color = Color.White) }
            }

            note?.let {
                Text(
                    it,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.6f))
                        .safeDrawingPadding()
                        .padding(16.dp),
                )
            }
        }
    }
}

// Writes the picture into Pictures/MayChat through the system gallery.
// On Android 10 and newer this needs no storage permission. Older Android
// versions would need one, so there the function simply reports failure.
private suspend fun saveImageToGallery(context: Context, bytes: ByteArray): Boolean =
    withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < 29) return@withContext false
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "MayChat_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/MayChat")
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return@withContext false
        val stream = resolver.openOutputStream(uri) ?: return@withContext false
        stream.use { it.write(bytes) }
        true
    }
