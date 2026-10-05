package com.maychat.app.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
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
suspend fun compressImage(context: Context, uri: Uri): ByteArray? = withContext(Dispatchers.IO) {
    try {
        val bitmap = loadScaledBitmap(context, uri) ?: return@withContext null
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
        out.toByteArray()
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }
}

private fun loadScaledBitmap(context: Context, uri: Uri): Bitmap? {
    if (Build.VERSION.SDK_INT >= 28) {
        // ImageDecoder also turns the photo the right way up.
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width
            val h = info.size.height
            val largest = maxOf(w, h)
            if (largest > MAX_IMAGE_SIDE) {
                val scale = MAX_IMAGE_SIDE.toFloat() / largest
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
    while (largest / (sample * 2) >= MAX_IMAGE_SIDE) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    val decoded = context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, options)
    } ?: return null
    val decodedLargest = maxOf(decoded.width, decoded.height)
    if (decodedLargest <= MAX_IMAGE_SIDE) return decoded
    val scale = MAX_IMAGE_SIDE.toFloat() / decodedLargest
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
@Composable
fun VoiceBubbleContent(path: String, durationMs: Int?, textColor: Color) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember(path) { mutableStateOf(false) }
    val playing = VoicePlayer.playingPath == path

    Row(
        modifier = Modifier
            .clickable(enabled = !loading) {
                loading = true
                scope.launch {
                    attempt { MediaCache.file(context, path) }
                        .onSuccess { VoicePlayer.toggle(it, path) }
                    loading = false
                }
            }
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
