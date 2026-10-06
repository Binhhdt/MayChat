package com.maychat.app.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// A ready-made background: two colors blended from top to bottom, one pair
// for light mode and a deeper pair for dark mode so text stays readable.
private class Preset(val id: String, val name: String, val light: List<Color>, val dark: List<Color>)

private val PRESETS = listOf(
    Preset("mint", "Bạc hà", listOf(Color(0xFFDFF3EF), Color(0xFFBFE6DF)), listOf(Color(0xFF0E2A29), Color(0xFF123B39))),
    Preset("peach", "Đào", listOf(Color(0xFFFFEBDD), Color(0xFFFFD3BD)), listOf(Color(0xFF2E1C16), Color(0xFF43271D))),
    Preset("sky", "Trời xanh", listOf(Color(0xFFE2EFFB), Color(0xFFC4DDF6)), listOf(Color(0xFF111F30), Color(0xFF172C45))),
    Preset("lilac", "Tím nhạt", listOf(Color(0xFFEEE7F8), Color(0xFFD9CCF0)), listOf(Color(0xFF1E1830), Color(0xFF2A2145))),
    Preset("sand", "Cát", listOf(Color(0xFFF6EFDD), Color(0xFFE9DDBB)), listOf(Color(0xFF28230F), Color(0xFF3A3215))),
)

// The chat background chosen on THIS phone. It applies to every conversation
// and is stored only on the phone; the other person does not see it.
object ChatWallpaper {
    private const val PREFS = "maychat_wallpaper"

    // "none", the id of a preset, or "custom" (a picture from the gallery).
    var choice by mutableStateOf("none")
        private set
    var customImage by mutableStateOf<Bitmap?>(null)
        private set

    private var loaded = false

    private fun file(context: Context) = File(context.filesDir, "chat_wallpaper.jpg")

    // Reads the saved choice once.
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val app = context.applicationContext
        val saved = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("choice", "none") ?: "none"
        if (saved == "custom") {
            val bitmap = runCatching { BitmapFactory.decodeFile(file(app).absolutePath) }.getOrNull()
            if (bitmap != null) {
                customImage = bitmap
                choice = "custom"
            }
        } else {
            choice = saved
        }
    }

    fun choose(context: Context, id: String) {
        choice = id
        if (id != "custom") customImage = null
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("choice", id).apply()
    }

    // Saves a picture (already shrunk to JPEG bytes) as the background.
    suspend fun chooseCustom(context: Context, jpegBytes: ByteArray): Boolean {
        val app = context.applicationContext
        val bitmap = withContext(Dispatchers.IO) {
            runCatching {
                file(app).writeBytes(jpegBytes)
                BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
            }.getOrNull()
        } ?: return false
        customImage = bitmap
        choose(app, "custom")
        return true
    }
}

@Composable
private fun presetBrush(preset: Preset): Brush =
    Brush.verticalGradient(if (isSystemInDarkTheme()) preset.dark else preset.light)

// Drawn behind the messages. Draws nothing for "none".
@Composable
fun ChatWallpaperLayer() {
    val context = LocalContext.current
    ChatWallpaper.load(context)

    val choice = ChatWallpaper.choice
    val picture = ChatWallpaper.customImage
    val preset = PRESETS.firstOrNull { it.id == choice }

    when {
        choice == "custom" && picture != null -> Box(modifier = Modifier.fillMaxSize()) {
            Image(
                bitmap = picture.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            // A light veil in the theme's ground color keeps times and
            // labels readable on any photo.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background.copy(alpha = 0.35f)),
            )
        }
        preset != null -> Box(modifier = Modifier.fillMaxSize().background(presetBrush(preset)))
        else -> {}
    }
}

// Window to pick the chat background.
@Composable
fun WallpaperDialog(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var error by androidx.compose.runtime.remember { mutableStateOf<String?>(null) }

    // The system photo picker needs no storage permission.
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                val bytes = compressImage(context, uri)
                val ok = bytes != null && ChatWallpaper.chooseCustom(context, bytes)
                if (ok) onClose() else error = "Không dùng được ảnh này làm hình nền."
            }
        }
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Hình nền trò chuyện") },
        text = {
            Column {
                Text(
                    "Hình nền chỉ hiện trên điện thoại này và áp dụng cho mọi cuộc trò chuyện.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))

                // Colored tiles: "none" first, then the presets.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    val selectedBorder = MaterialTheme.colorScheme.primary
                    val normalBorder = MaterialTheme.colorScheme.outlineVariant

                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.background)
                            .border(
                                2.dp,
                                if (ChatWallpaper.choice == "none") selectedBorder else normalBorder,
                                RoundedCornerShape(12.dp),
                            )
                            .clickable { ChatWallpaper.choose(context, "none") },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("✕", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    PRESETS.forEach { preset ->
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(presetBrush(preset))
                                .border(
                                    2.dp,
                                    if (ChatWallpaper.choice == preset.id) selectedBorder else normalBorder,
                                    RoundedCornerShape(12.dp),
                                )
                                .clickable { ChatWallpaper.choose(context, preset.id) },
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
                OutlinedButton(
                    onClick = {
                        pickImage.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Chọn ảnh từ máy") }

                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Xong") } },
    )
}
