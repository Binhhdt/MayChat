package com.maychat.app.ui.chat

import android.media.MediaMetadataRetriever
import android.util.LruCache
import android.widget.VideoView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.maychat.app.R
import com.maychat.app.data.MediaCache
import com.maychat.app.data.attempt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

// ---------------------------------------------------------------------
// Images
// ---------------------------------------------------------------------

private const val MAX_IMAGE_SIDE = 1280

// Reads the picked photo, shrinks it so the longest side is at most 1280
// pixels and saves it as JPEG. A 5 MB camera photo becomes roughly 200 KB.
// Returns null if the photo cannot be read.
suspend fun compressImage(
    context: Context,
    uri: Uri,
    maxSide: Int = MAX_IMAGE_SIDE,
): ByteArray? = withContext(Dispatchers.IO) {
    try {
        val bitmap = loadScaledBitmap(context, uri, maxSide) ?: return@withContext null
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
        out.toByteArray()
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }
}

private fun loadScaledBitmap(context: Context, uri: Uri, maxSide: Int): Bitmap? {
    if (Build.VERSION.SDK_INT >= 28) {
        // ImageDecoder also turns the photo the right way up.
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width
            val h = info.size.height
            val largest = maxOf(w, h)
            if (largest > maxSide) {
                val scale = maxSide.toFloat() / largest
                decoder.setTargetSize(
                    (w * scale).toInt().coerceAtLeast(1),
                    (h * scale).toInt().coerceAtLeast(1),
                )
            }
        }
    }

    // Android 8.0 / 8.1: older way of reading a picture in reduced size.
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    val largest = maxOf(bounds.outWidth, bounds.outHeight)
    if (largest <= 0) return null
    var sample = 1
    while (largest / (sample * 2) >= maxSide) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    val decoded = context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, options)
    } ?: return null
    val decodedLargest = maxOf(decoded.width, decoded.height)
    if (decodedLargest <= maxSide) return decoded
    val scale = maxSide.toFloat() / decodedLargest
    return Bitmap.createScaledBitmap(
        decoded,
        (decoded.width * scale).toInt().coerceAtLeast(1),
        (decoded.height * scale).toInt().coerceAtLeast(1),
        true,
    )
}

// Shows one image message. Downloads it the first time, then uses the cache.
@Composable
fun ChatImage(path: String) {
    val bitmap by produceState(initialValue = MediaCache.cachedBitmap(path), path) {
        if (value == null) {
            value = attempt { MediaCache.bitmap(path) }.getOrNull()
        }
    }
    val loaded = bitmap
    if (loaded == null) {
        Box(
            modifier = Modifier
                .size(width = 180.dp, height = 120.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text("Đang tải ảnh…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        Image(
            bitmap = loaded.asImageBitmap(),
            contentDescription = "Ảnh",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .widthIn(max = 240.dp)
                .heightIn(max = 320.dp)
                .clip(RoundedCornerShape(12.dp)),
        )
    }
}

// ---------------------------------------------------------------------
// Voice messages: recording
// ---------------------------------------------------------------------

class Recording(val bytes: ByteArray, val durationMs: Int)

// Records the microphone into a small AAC (.m4a) file: mono, 32 kbit/s,
// about 240 KB per minute.
class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L

    val elapsedMs: Long
        get() = if (recorder == null) 0L else SystemClock.elapsedRealtime() - startedAt

    // Returns false when the microphone cannot be used right now.
    fun start(): Boolean {
        cancel()
        val target = File(context.cacheDir, "recording-${System.currentTimeMillis()}.m4a")
        return try {
            @Suppress("DEPRECATION")
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(16000)
            r.setAudioEncodingBitRate(32000)
            r.setOutputFile(target.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            file = target
            startedAt = SystemClock.elapsedRealtime()
            true
        } catch (e: Exception) {
            target.delete()
            recorder = null
            file = null
            false
        }
    }

    // Stops and returns the recording, or null if it is too short or broken.
    fun stop(): Recording? {
        val r = recorder ?: return null
        val target = file
        val duration = (SystemClock.elapsedRealtime() - startedAt).toInt()
        recorder = null
        file = null
        val ok = try {
            r.stop()
            true
        } catch (e: RuntimeException) {
            false
        } finally {
            r.release()
        }
        if (!ok || target == null || duration < 700) {
            target?.delete()
            return null
        }
        val bytes = try {
            target.readBytes()
        } catch (e: Exception) {
            null
        }
        target.delete()
        return if (bytes == null || bytes.isEmpty()) null else Recording(bytes, duration)
    }

    // Stops and throws the recording away.
    fun cancel() {
        val r = recorder
        recorder = null
        if (r != null) {
            try {
                r.stop()
            } catch (e: RuntimeException) {
                // Nothing was recorded yet.
            }
            r.release()
        }
        file?.delete()
        file = null
    }
}

// ---------------------------------------------------------------------
// Voice messages: playback (only one plays at a time)
// ---------------------------------------------------------------------

object VoicePlayer {
    // The storage path of the voice message that is playing now, if any.
    var playingPath by mutableStateOf<String?>(null)
        private set

    private var player: MediaPlayer? = null

    fun toggle(file: File, path: String) {
        if (playingPath == path) {
            stop()
            return
        }
        stop()
        try {
            val p = MediaPlayer()
            p.setDataSource(file.absolutePath)
            p.setOnCompletionListener { stop() }
            p.prepare()
            p.start()
            player = p
            playingPath = path
        } catch (e: Exception) {
            stop()
        }
    }

    fun stop() {
        val p = player
        player = null
        playingPath = null
        if (p != null) {
            try {
                p.stop()
            } catch (e: IllegalStateException) {
                // Was not playing.
            }
            p.release()
        }
    }
}

fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    return "${totalSeconds / 60}:${(totalSeconds % 60).toString().padStart(2, '0')}"
}

// Shows one voice message: a play/stop button and the length.
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VoiceBubbleContent(
    path: String,
    durationMs: Int?,
    textColor: Color,
    onLongPress: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember(path) { mutableStateOf(false) }
    val playing = VoicePlayer.playingPath == path

    Row(
        modifier = Modifier
            .combinedClickable(
                enabled = !loading,
                onLongClick = onLongPress,
                onClick = {
                    loading = true
                    scope.launch {
                        attempt { MediaCache.file(context, path) }
                            .onSuccess { VoicePlayer.toggle(it, path) }
                        loading = false
                    }
                },
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            when {
                loading -> "…"
                playing -> "⏹"
                else -> "▶"
            },
            color = textColor,
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            "Tin nhắn thoại" + (durationMs?.let { " · " + formatDuration(it.toLong()) } ?: ""),
            color = textColor,
        )
    }
}

// ---------------------------------------------------------------------
// Files
// ---------------------------------------------------------------------

const val MAX_FILE_BYTES = 5 * 1024 * 1024

class PickedFile(val name: String, val bytes: ByteArray)

// Reads the file the user picked. Returns null when it cannot be read.
// Throws IllegalArgumentException when it is larger than 5 MB.
suspend fun readPickedFile(context: Context, uri: Uri): PickedFile? = withContext(Dispatchers.IO) {
    var name = "file"
    var size = -1L
    try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameColumn >= 0) cursor.getString(nameColumn)?.let { name = it }
                if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) size = cursor.getLong(sizeColumn)
            }
        }
    } catch (e: Exception) {
        // Name and size stay unknown; the size is checked again below.
    }
    if (size > MAX_FILE_BYTES) throw IllegalArgumentException("too large")

    val bytes = try {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    } catch (e: Exception) {
        null
    } ?: return@withContext null
    if (bytes.size > MAX_FILE_BYTES) throw IllegalArgumentException("too large")
    if (bytes.isEmpty()) return@withContext null
    PickedFile(name.take(200), bytes)
}

fun formatFileSize(bytes: Int?): String {
    if (bytes == null) return ""
    return when {
        bytes >= 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024f * 1024f))
        bytes >= 1024 -> "${bytes / 1024} KB"
        else -> "$bytes B"
    }
}

// Downloads a received file (once) and opens it with a suitable app of the
// phone. Returns false when no app can open this kind of file.
private suspend fun openChatFile(context: Context, path: String, fileName: String): Boolean {
    val bytes = MediaCache.bytes(path)
    val file = withContext(Dispatchers.IO) {
        val folder = File(context.cacheDir, "shared")
        folder.mkdirs()
        // Keep only harmless characters of the name for the local copy.
        val safeName = fileName.replace(Regex("[^A-Za-z0-9._ -]"), "_").ifBlank { "file" }
        File(folder, safeName).also { it.writeBytes(bytes) }
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val extension = fileName.substringAfterLast('.', "").lowercase()
    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "*/*"
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return try {
        context.startActivity(intent)
        true
    } catch (e: Exception) {
        false
    }
}

// Shows one file message: a paper-clip, the file name and its size.
// Tap to download and open, press and hold for the menu.
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileBubbleContent(
    path: String,
    fileName: String,
    fileSize: Int?,
    textColor: Color,
    onLongPress: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember(path) { mutableStateOf(false) }
    var note by remember(path) { mutableStateOf<String?>(null) }

    Row(
        modifier = Modifier
            .widthIn(max = 280.dp)
            .combinedClickable(
                enabled = !busy,
                onLongClick = onLongPress,
                onClick = {
                    busy = true
                    note = null
                    scope.launch {
                        val opened = attempt { openChatFile(context, path, fileName) }
                        note = when {
                            opened.isFailure -> "Không tải được file."
                            opened.getOrDefault(false) -> null
                            else -> "Điện thoại không có ứng dụng mở loại file này."
                        }
                        busy = false
                    }
                },
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_attach),
            contentDescription = null,
            tint = textColor,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                fileName,
                color = textColor,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                note ?: if (busy) "Đang mở…" else formatFileSize(fileSize),
                color = textColor,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

// ---------------------------------------------------------------------
// Videos (sent as files, shown with a preview and played inside the app)
// ---------------------------------------------------------------------

fun isVideoFile(fileName: String?): Boolean =
    (fileName ?: "").substringAfterLast('.', "").lowercase() in
        setOf("mp4", "m4v", "3gp", "webm", "mkv", "mov")

// First frame of each video already looked at, at most 20.
private val videoThumbs = LruCache<String, Bitmap>(20)

private suspend fun videoThumbnail(context: Context, path: String): Bitmap? {
    videoThumbs.get(path)?.let { return it }
    val file = MediaCache.file(context, path)
    return withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            retriever.getFrameAtTime(0)?.also { videoThumbs.put(path, it) }
        } catch (e: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }
}

// Shows one video message: its first frame with a play button.
// Tap to play it inside the app, press and hold for the menu.
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VideoBubbleContent(path: String, onLongPress: () -> Unit = {}) {
    val context = LocalContext.current
    var playing by remember(path) { mutableStateOf(false) }
    val thumb by produceState(initialValue = videoThumbs.get(path), path) {
        if (value == null) value = attempt { videoThumbnail(context, path) }.getOrNull()
    }
    val frame = thumb

    Box(
        modifier = Modifier
            .size(width = 220.dp, height = 150.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black)
            .combinedClickable(onLongClick = onLongPress, onClick = { playing = true }),
        contentAlignment = Alignment.Center,
    ) {
        if (frame != null) {
            Image(
                bitmap = frame.asImageBitmap(),
                contentDescription = "Video",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.55f)),
            contentAlignment = Alignment.Center,
        ) {
            Text("▶", color = Color.White, fontSize = 22.sp)
        }
    }

    if (playing) {
        VideoPlayerDialog(path = path, onClose = { playing = false })
    }
}

// Plays one video on the whole screen. Tap the picture to pause or continue.
@Composable
private fun VideoPlayerDialog(path: String, onClose: () -> Unit) {
    val context = LocalContext.current
    // null = still downloading; the file is at most 5 MB.
    var file by remember(path) { mutableStateOf<File?>(null) }
    var failed by remember(path) { mutableStateOf(false) }
    var paused by remember(path) { mutableStateOf(false) }
    var player by remember(path) { mutableStateOf<VideoView?>(null) }

    LaunchedEffect(path) {
        attempt { MediaCache.file(context, path) }
            .onSuccess { file = it }
            .onFailure { failed = true }
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            val ready = file
            when {
                failed -> Text(
                    "Không phát được video này.",
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center),
                )
                ready == null -> Text(
                    "Đang tải video…",
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center),
                )
                else -> {
                    AndroidView(
                        factory = { viewContext ->
                            VideoView(viewContext).apply {
                                setVideoPath(ready.absolutePath)
                                setOnPreparedListener { start() }
                                setOnCompletionListener { paused = true }
                                setOnErrorListener { _, _, _ ->
                                    failed = true
                                    true
                                }
                                player = this
                            }
                        },
                        modifier = Modifier.fillMaxWidth().align(Alignment.Center),
                    )
                    // Transparent layer on top: tap to pause or continue.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable {
                                val view = player ?: return@clickable
                                if (view.isPlaying) {
                                    view.pause()
                                    paused = true
                                } else {
                                    view.start()
                                    paused = false
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (paused) {
                            Box(
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(CircleShape)
                                    .background(Color.Black.copy(alpha = 0.55f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("▶", color = Color.White, fontSize = 30.sp)
                            }
                        }
                    }
                }
            }
            TextButton(
                onClick = onClose,
                modifier = Modifier.align(Alignment.TopStart).safeDrawingPadding(),
            ) { Text("‹ Đóng", color = Color.White) }
        }
    }
}
