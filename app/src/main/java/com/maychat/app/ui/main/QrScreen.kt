package com.maychat.app.ui.main

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.PlanarYUVLuminanceSource
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.maychat.app.data.ChatRepository
import com.maychat.app.data.Profile
import com.maychat.app.data.attempt
import com.maychat.app.data.toUserMessage
import com.maychat.app.ui.chat.compressImage
import com.maychat.app.ui.common.Avatar
import com.maychat.app.ui.common.BackButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// What a MayChat QR code contains: this prefix followed by the username.
private const val QR_PREFIX = "maychat:user:"

// "Mã QR": shows my own code for others to scan, and reads a friend's code:
// live with the camera (point at the code, it is read by itself), from a
// photo taken now, or from a picture in the gallery.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrScreen(
    myId: String,
    friends: FriendsState,
    onOpenChat: (Profile) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var me by remember { mutableStateOf<Profile?>(null) }
    var myCode by remember { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // The person whose code was just read.
    var found by remember { mutableStateOf<Profile?>(null) }

    LaunchedEffect(myId) {
        attempt { ChatRepository.loadProfile(myId) }.onSuccess { profile ->
            me = profile
            if (profile != null) {
                myCode = withContext(Dispatchers.Default) { makeQr(QR_PREFIX + profile.username) }
            }
        }
    }

    // Whether the live scanner is open.
    var scanning by remember { mutableStateOf(false) }

    // Looks up the person a code belongs to. text: what the code contains,
    // or null when no code was found.
    fun lookUp(text: String?, decode: (suspend () -> String?)? = null) {
        busy = true
        error = null
        scope.launch {
            val text = text ?: decode?.invoke()
            val username = text?.takeIf { it.startsWith(QR_PREFIX) }?.removePrefix(QR_PREFIX)?.trim()
            when {
                text == null ->
                    error = "Không thấy mã QR trong ảnh. Hãy chụp gần hơn, đủ sáng và rõ nét."
                username.isNullOrEmpty() ->
                    error = "Đây không phải mã QR của MayChat."
                username == me?.username ->
                    error = "Đây là mã QR của chính bạn."
                else -> attempt { ChatRepository.searchUsers(username, myId) }
                    .onSuccess { list ->
                        val person = list.firstOrNull { it.username == username }
                        if (person == null) error = "Không tìm thấy người dùng @$username." else found = person
                    }
                    .onFailure { error = it.toUserMessage() }
            }
            busy = false
        }
    }

    // Reads the code in a picture and looks the person up.
    fun readFrom(bytes: ByteArray?) {
        if (bytes == null) {
            error = "Không đọc được ảnh này."
            return
        }
        lookUp(null) { withContext(Dispatchers.Default) { decodeQr(bytes) } }
    }

    val askCameraForScan = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            scanning = true
        } else {
            error = "Cần quyền camera để quét mã QR. Bạn vẫn có thể chọn ảnh có mã QR từ máy."
        }
    }

    // Photo taken now. The file has a fixed place, so the result is still
    // usable if Android re-creates the screen while the camera is open.
    val photoFile = remember { File(File(context.cacheDir, "camera"), "qr-scan.jpg") }
    val photoUri = remember {
        photoFile.parentFile?.mkdirs()
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", photoFile)
    }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        if (saved) scope.launch { readFrom(compressImage(context, photoUri, maxSide = 1600)) }
    }
    // The app now lists the camera permission (for video calls), so Android
    // only lets it open the camera app once that permission is granted.
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            runCatching { takePhoto.launch(photoUri) }
                .onFailure { error = "Không mở được máy ảnh trên điện thoại này." }
        } else {
            error = "Cần quyền camera để chụp mã QR. Bạn vẫn có thể chọn ảnh có mã QR từ máy."
        }
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch { readFrom(compressImage(context, uri, maxSide = 1600)) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Mã QR") },
                navigationIcon = { BackButton(onClick = onBack) },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Mã của tôi", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Cho bạn bè chụp mã này bằng MayChat để kết bạn với bạn.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))

            // The code is always black on white, also in dark mode, so every
            // camera can read it.
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.White)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val code = myCode
                if (code != null) {
                    Image(
                        bitmap = code.asImageBitmap(),
                        contentDescription = "Mã QR của tôi",
                        modifier = Modifier.size(240.dp),
                    )
                } else {
                    Spacer(Modifier.size(240.dp))
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    me?.let { "${it.displayName}  ·  @${it.username}" } ?: "",
                    color = Color(0xFF10201F),
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Spacer(Modifier.height(28.dp))
            Text("Quét mã của bạn bè", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            // Live: point the camera at the code.
            Button(
                onClick = {
                    error = null
                    val cameraGranted = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.CAMERA,
                    ) == PackageManager.PERMISSION_GRANTED
                    if (cameraGranted) scanning = true else askCameraForScan.launch(Manifest.permission.CAMERA)
                },
                enabled = !busy,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text(if (busy) "Đang đọc mã…" else "Quét mã QR") }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = {
                    val cameraGranted = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.CAMERA,
                    ) == PackageManager.PERMISSION_GRANTED
                    if (!cameraGranted) {
                        askCamera.launch(Manifest.permission.CAMERA)
                    } else {
                        try {
                            takePhoto.launch(photoUri)
                        } catch (e: Exception) {
                            error = "Không mở được máy ảnh trên điện thoại này."
                        }
                    }
                },
                enabled = !busy,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Chụp ảnh mã QR") }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = {
                    pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                enabled = !busy,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Chọn ảnh có mã QR") }

            error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            }
        }
    }

    if (scanning) {
        QrLiveScanner(
            onFound = { text ->
                scanning = false
                lookUp(text)
            },
            onFailed = {
                scanning = false
                error = "Không mở được camera để quét. Hãy thử \"Chụp ảnh mã QR\"."
            },
            onClose = { scanning = false },
        )
    }

    // The person found by the code: add as friend, or open the chat.
    found?.let { person ->
        val relation = friends.relation(person.id)
        AlertDialog(
            onDismissRequest = { found = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(name = person.displayName, online = false, size = 44.dp, avatarPath = person.avatarPath)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(person.displayName, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "@${person.username}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            text = {
                Text(
                    when (relation) {
                        Relation.FRIEND -> "Hai bạn đã là bạn bè."
                        Relation.REQUEST_SENT -> "Bạn đã gửi lời mời kết bạn, đang chờ trả lời."
                        Relation.REQUEST_RECEIVED -> "Người này đã gửi lời mời kết bạn cho bạn."
                        Relation.BLOCKED -> "Bạn đang chặn người này."
                        Relation.NONE -> "Gửi lời mời kết bạn tới người này?"
                    },
                )
            },
            confirmButton = {
                when (relation) {
                    Relation.NONE -> TextButton(onClick = { friends.sendRequest(person.id) }) { Text("Kết bạn") }
                    Relation.REQUEST_RECEIVED ->
                        TextButton(onClick = { friends.accept(person.id) }) { Text("Chấp nhận") }
                    Relation.BLOCKED -> {}
                    else -> TextButton(
                        onClick = {
                            found = null
                            onOpenChat(person)
                        },
                    ) { Text("Nhắn tin") }
                }
            },
            dismissButton = { TextButton(onClick = { found = null }) { Text("Đóng") } },
        )
    }
}

// Draws the text as a QR code, black on white.
private fun makeQr(text: String, size: Int = 720): Bitmap? =
    try {
        val matrix = QRCodeWriter().encode(
            text,
            BarcodeFormat.QR_CODE,
            size,
            size,
            mapOf(EncodeHintType.MARGIN to 1),
        )
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                pixels[y * size + x] = if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
        }
        Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    } catch (e: Exception) {
        null
    }

// Finds a QR code in a picture (JPEG bytes) and returns its text, or null.
private fun decodeQr(jpegBytes: ByteArray): String? {
    val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size) ?: return null
    val width = bitmap.width
    val height = bitmap.height
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
    val source = RGBLuminanceSource(width, height, pixels)
    val hints = mapOf(DecodeHintType.TRY_HARDER to true)

    // Two ways of turning the photo into black and white; the second one
    // sometimes works on photos where the first does not.
    val attempts = listOf(
        { BinaryBitmap(HybridBinarizer(source)) },
        { BinaryBitmap(GlobalHistogramBinarizer(source)) },
    )
    for (make in attempts) {
        try {
            return QRCodeReader().decode(make(), hints).text
        } catch (e: Exception) {
            // Not found this way: try the next one.
        }
    }
    return null
}

// Full-screen live scanner: shows the camera picture and reads a MayChat
// QR code as soon as one is in view. The camera permission has been
// granted before this is shown.
@Composable
private fun QrLiveScanner(onFound: (String) -> Unit, onFailed: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    // The first code read ends the scan; later frames are ignored.
    val done = remember { AtomicBoolean(false) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    val cameraController = remember {
        LifecycleCameraController(context).apply {
            cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
            imageAnalysisBackpressureStrategy = ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
        }
    }

    DisposableEffect(cameraController, lifecycleOwner) {
        try {
            cameraController.setImageAnalysisAnalyzer(executor) { image ->
                try {
                    if (!done.get()) {
                        val text = decodeFrame(image)
                        if (text != null && text.startsWith(QR_PREFIX) && done.compareAndSet(false, true)) {
                            mainHandler.post { onFound(text) }
                        }
                    }
                } catch (e: Exception) {
                    // A frame that cannot be read: wait for the next one.
                } finally {
                    image.close()
                }
            }
            cameraController.bindToLifecycle(lifecycleOwner)
        } catch (e: Exception) {
            mainHandler.post { onFailed() }
        }
        onDispose {
            runCatching { cameraController.clearImageAnalysisAnalyzer() }
            runCatching { cameraController.unbind() }
            executor.shutdown()
        }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                factory = { viewContext ->
                    PreviewView(viewContext).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        controller = cameraController
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            // Frame that shows where to hold the code.
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(250.dp)
                    .border(3.dp, Color.White, RoundedCornerShape(24.dp)),
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .safeDrawingPadding()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "Đưa mã QR MayChat của bạn bè vào khung",
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = onClose, shape = RoundedCornerShape(16.dp)) { Text("Đóng") }
            }
        }
    }
}

// Reads a QR code from one camera frame (its brightness plane), or null.
private fun decodeFrame(image: ImageProxy): String? {
    val plane = image.planes.firstOrNull() ?: return null
    val width = image.width
    val height = image.height
    val rowStride = plane.rowStride
    val buffer = plane.buffer
    // Copy row by row: a row in the buffer can be longer than the picture.
    val data = ByteArray(width * height)
    for (row in 0 until height) {
        val start = row * rowStride
        if (start + width > buffer.limit()) return null
        buffer.position(start)
        buffer.get(data, row * width, width)
    }
    val source = PlanarYUVLuminanceSource(data, width, height, 0, 0, width, height, false)
    return try {
        QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source))).text
    } catch (e: Exception) {
        null
    }
}
