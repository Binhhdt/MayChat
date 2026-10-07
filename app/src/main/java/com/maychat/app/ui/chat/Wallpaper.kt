package com.maychat.app.ui.chat

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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
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
import com.maychat.app.data.MediaCache
import com.maychat.app.data.attempt
import kotlinx.coroutines.launch

// A ready-made background: two colors blended from top to bottom, one pair
// for light mode and a deeper pair for dark mode so text stays readable.
// The ids are also known to the database (see supabase_migration_13).
private class Preset(val id: String, val light: List<Color>, val dark: List<Color>)

private val PRESETS = listOf(
    Preset("mint", listOf(Color(0xFFDFF3EF), Color(0xFFBFE6DF)), listOf(Color(0xFF0E2A29), Color(0xFF123B39))),
    Preset("peach", listOf(Color(0xFFFFEBDD), Color(0xFFFFD3BD)), listOf(Color(0xFF2E1C16), Color(0xFF43271D))),
    Preset("sky", listOf(Color(0xFFE2EFFB), Color(0xFFC4DDF6)), listOf(Color(0xFF111F30), Color(0xFF172C45))),
    Preset("lilac", listOf(Color(0xFFEEE7F8), Color(0xFFD9CCF0)), listOf(Color(0xFF1E1830), Color(0xFF2A2145))),
    Preset("sand", listOf(Color(0xFFF6EFDD), Color(0xFFE9DDBB)), listOf(Color(0xFF28230F), Color(0xFF3A3215))),
)

@Composable
private fun presetBrush(preset: Preset): Brush =
    Brush.verticalGradient(if (isSystemInDarkTheme()) preset.dark else preset.light)

// Drawn behind the messages. The value is the conversation's background as
// stored on the server, so both people see the same:
//   null        -> nothing
//   "mint" ...  -> a ready-made color background
//   "img:path"  -> a picture from the conversation's storage folder
@Composable
fun ChatWallpaperLayer(wallpaper: String?) {
    if (wallpaper == null) return
    val preset = PRESETS.firstOrNull { it.id == wallpaper }

    if (preset != null) {
        Box(modifier = Modifier.fillMaxSize().background(presetBrush(preset)))
        return
    }
    if (!wallpaper.startsWith("img:")) return

    val path = wallpaper.removePrefix("img:")
    val picture by produceState(initialValue = MediaCache.cachedBitmap(path), path) {
        // Always set for the CURRENT path. Keeping the old value here is
        // what made a newly chosen picture appear only after leaving and
        // re-opening the chat.
        this.value = MediaCache.cachedBitmap(path) ?: attempt { MediaCache.bitmap(path) }.getOrNull()
    }
    val loaded = picture ?: return
    Box(modifier = Modifier.fillMaxSize()) {
        Image(
            bitmap = loaded.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        // A light veil in the theme's ground color keeps times and labels
        // readable on any photo.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.35f)),
        )
    }
}

// Window to pick the background of THIS conversation. The choice is saved
// on the server and shows up on the other person's phone too.
@Composable
fun WallpaperDialog(
    current: String?,
    onChoose: (String?) -> Unit,
    onChooseCustom: (ByteArray) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    // The tile ticked in this window. Nothing is changed for real until
    // "Áp dụng" is pressed, so trying several colors makes only ONE change
    // (and only one "đã thay đổi hình nền" line in the chat).
    var picked by remember { mutableStateOf(current) }

    // The system photo picker needs no storage permission.
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                val bytes = compressImage(context, uri)
                if (bytes == null) {
                    error = "Không dùng được ảnh này làm hình nền."
                } else {
                    onChooseCustom(bytes)
                    onClose()
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Hình nền trò chuyện") },
        text = {
            Column {
                Text(
                    "Chọn một màu rồi bấm Áp dụng. Hình nền áp dụng cho cuộc trò chuyện này và cả hai người cùng thấy.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))

                // Colored tiles: "none" first, then the ready-made backgrounds.
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
                                if (picked == null) selectedBorder else normalBorder,
                                RoundedCornerShape(12.dp),
                            )
                            .clickable { picked = null },
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
                                    if (picked == preset.id) selectedBorder else normalBorder,
                                    RoundedCornerShape(12.dp),
                                )
                                .clickable { picked = preset.id },
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
        confirmButton = {
            TextButton(
                onClick = {
                    if (picked != current) onChoose(picked)
                    onClose()
                },
            ) { Text("Áp dụng") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Hủy") } },
    )
}
