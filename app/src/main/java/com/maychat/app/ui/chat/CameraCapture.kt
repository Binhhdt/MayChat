package com.maychat.app.ui.chat

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.maychat.app.data.ChatRepository
import com.maychat.app.data.MediaCache
import com.maychat.app.data.NewGroupMessage
import com.maychat.app.data.attempt
import com.maychat.app.data.toUserMessage
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID

// Takes a photo with the phone's camera app and sends it into a chat.
//
// While the camera app is open, Android often closes MayChat's screen to
// free memory (camera apps need a lot). A photo handled by the chat screen
// would then be lost. So here the request is written to disk BEFORE the
// camera opens, and the result is handled by MainActivity, which Android
// always brings back. The photo is then sent no matter which screen shows.
object CameraCapture {
    private const val PREFS = "maychat_camera"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Set by MainActivity: opens the camera app for the given file.
    var launcher: ((Uri) -> Unit)? = null

    private val _sending = MutableStateFlow(false)

    // True while a taken photo is being uploaded.
    val sending: StateFlow<Boolean> = _sending.asStateFlow()

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    private fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    // Opens the camera. Returns false if this phone cannot do it.
    // group = true: conversationId is the id of a GROUP, and the photo is
    // sent into that group.
    fun start(context: Context, conversationId: String, group: Boolean = false): Boolean {
        val open = launcher ?: return false
        return try {
            val folder = File(context.cacheDir, "camera")
            folder.mkdirs()
            val photo = File(folder, "photo-${System.currentTimeMillis()}.jpg")
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("conversation_id", conversationId)
                .putBoolean("group", group)
                .putString("file", photo.absolutePath)
                .apply()
            open(uriFor(context, photo))
            true
        } catch (e: Exception) {
            false
        }
    }

    // Called by MainActivity when the camera app closes.
    fun onResult(context: Context, saved: Boolean) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val conversationId = prefs.getString("conversation_id", null)
        val filePath = prefs.getString("file", null)
        val toGroup = prefs.getBoolean("group", false)
        prefs.edit().clear().apply()
        if (conversationId == null || filePath == null) return
        val file = File(filePath)
        if (!saved || !file.exists() || file.length() == 0L) {
            file.delete()
            return
        }

        _sending.value = true
        scope.launch {
            val result = attempt {
                // After Android restarted the app, wait until the saved login is loaded.
                withTimeoutOrNull(15_000) {
                    ChatRepository.sessionStatus.first { it is SessionStatus.Authenticated }
                } ?: throw IllegalStateException("not logged in")

                val bytes = compressImage(appContext, uriFor(appContext, file))
                    ?: throw IllegalStateException("cannot read photo")
                val path = "$conversationId/${UUID.randomUUID()}.jpg"
                ChatRepository.uploadMedia(path, bytes)
                MediaCache.put(path, bytes)
                if (toGroup) {
                    ChatRepository.sendGroupMessage(
                        NewGroupMessage(groupId = conversationId, content = "📷 Ảnh", kind = "image", mediaPath = path),
                    )
                } else {
                    ChatRepository.sendMediaMessage(conversationId, "image", path, "📷 Ảnh", null)
                }
            }
            file.delete()
            _sending.value = false
            result.onFailure { _errors.emit("Không gửi được ảnh vừa chụp. " + it.toUserMessage()) }
        }
    }
}
