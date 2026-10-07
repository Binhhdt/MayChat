package com.maychat.app.ui.chat

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.util.LruCache
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.maychat.app.R
import com.maychat.app.data.ChatRepository
import com.maychat.app.data.Profile
import com.maychat.app.data.attempt
import com.maychat.app.ui.common.Avatar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

// =====================================================================
// Stickers
// The pictures are Google's "Noto Emoji" (free to use), fetched while the
// APK is built and packed into the app (see app/build.gradle.kts). A
// sticker is identified by the code of its emoji, for example "1f602".
// When a picture is missing (the download failed during the build) the
// emoji itself is shown large instead, so stickers always work.
// =====================================================================

val STICKERS: List<String> = listOf(
    "1f600", "1f602", "1f923", "1f60d", "1f970", "1f618", "1f60e", "1f914",
    "1f62d", "1f621", "1f631", "1f634", "1f97a", "1f644", "1f92d", "1f973",
    "1f60b", "1f92f", "1f975", "1f976", "1f44d", "1f44e", "1f44f", "1f64f",
    "1f4aa", "1f44c", "1f91d", "270c", "2764", "1f494", "1f495", "1f525",
    "2728", "1f389", "1f382", "1f339", "2615", "1f35c", "1f37b", "26bd",
    "1f3b5", "1f4af", "1f436", "1f431", "1f43c", "1f984", "1f308", "1f31f",
)

// The emoji a sticker code stands for ("1f602" -> 😂).
fun stickerEmoji(code: String): String {
    val point = code.toIntOrNull(16) ?: return "🙂"
    return try {
        val text = String(Character.toChars(point))
        // Older symbols need a mark to be drawn in color.
        if (point < 0x1F000) text + "️" else text
    } catch (e: IllegalArgumentException) {
        "🙂"
    }
}

// The text of a sticker message, as lists and notifications show it.
fun stickerText(code: String): String = "Sticker " + stickerEmoji(code)

private val stickerCache = LruCache<String, ImageBitmap>(32)

// The picture of a sticker from the app's own files, or null when it is
// not there.
private suspend fun loadSticker(context: Context, code: String): ImageBitmap? {
    val clean = code.filter { it.isLetterOrDigit() }.lowercase()
    stickerCache.get(clean)?.let { return it }
    return withContext(Dispatchers.IO) {
        try {
            context.assets.open("stickers/emoji_u$clean.png").use { input ->
                // Half size (256 pixels) is plenty for a sticker and saves memory.
                val options = BitmapFactory.Options().apply { inSampleSize = 2 }
                BitmapFactory.decodeStream(input, null, options)?.asImageBitmap()
            }?.also { stickerCache.put(clean, it) }
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }
}

// One sticker. fallback: the emoji shown when there is no picture.
@Composable
fun StickerImage(code: String?, fallback: String, size: Dp = 120.dp) {
    val context = LocalContext.current
    val key = code ?: ""
    val picture by produceState(initialValue = stickerCache.get(key.lowercase()), key) {
        if (value == null && key.isNotEmpty()) value = loadSticker(context, key)
    }
    val loaded = picture
    Box(modifier = Modifier.size(size), contentAlignment = Alignment.Center) {
        if (loaded != null) {
            Image(bitmap = loaded, contentDescription = fallback, modifier = Modifier.fillMaxSize())
        } else {
            Text(
                fallback.ifBlank { if (key.isEmpty()) "🙂" else stickerEmoji(key) },
                fontSize = (size.value * 0.6f).sp,
            )
        }
    }
}

// The grid of stickers under the text box. Tap one to send it at once.
@Composable
fun StickerGrid(onPick: (String) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(5),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(8.dp),
    ) {
        items(STICKERS) { code ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onPick(code) }
                    .padding(6.dp),
                contentAlignment = Alignment.Center,
            ) {
                StickerImage(code = code, fallback = stickerEmoji(code), size = 56.dp)
            }
        }
    }
}

// =====================================================================
// The paper-clip button: file, my location, a contact card
// =====================================================================

@Composable
fun AttachMenuButton(
    enabled: Boolean,
    onFile: () -> Unit,
    onLocation: () -> Unit,
    onContact: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.size(40.dp)) {
            Icon(
                painter = painterResource(R.drawable.ic_attach),
                contentDescription = "Gửi file, vị trí, danh thiếp",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("📎  File") },
                onClick = {
                    open = false
                    onFile()
                },
            )
            DropdownMenuItem(
                text = { Text("📍  Vị trí của tôi") },
                onClick = {
                    open = false
                    onLocation()
                },
            )
            DropdownMenuItem(
                text = { Text("👤  Danh thiếp") },
                onClick = {
                    open = false
                    onContact()
                },
            )
        }
    }
}

// =====================================================================
// Location
// A location message carries "latitude,longitude" in its extra data.
// =====================================================================

// Where the phone is now, or null when it cannot be found within 20
// seconds. The caller has made sure the permission is granted.
@SuppressLint("MissingPermission")
private suspend fun currentLocation(context: Context): Location? {
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
    val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        .filter { name -> runCatching { manager.isProviderEnabled(name) }.getOrDefault(false) }
    if (providers.isEmpty()) return null

    // A position found by the phone in the last two minutes is good enough.
    val last = providers
        .mapNotNull { name -> runCatching { manager.getLastKnownLocation(name) }.getOrNull() }
        .maxByOrNull { it.time }
    if (last != null && System.currentTimeMillis() - last.time < 120_000) return last

    val fresh = withTimeoutOrNull(20_000) {
        suspendCancellableCoroutine<Location?> { continuation ->
            val listeners = ArrayList<LocationListener>()
            fun finish(location: Location?) {
                listeners.forEach { runCatching { manager.removeUpdates(it) } }
                if (continuation.isActive) continuation.resume(location)
            }
            try {
                for (name in providers) {
                    if (Build.VERSION.SDK_INT >= 30) {
                        manager.getCurrentLocation(name, null, context.mainExecutor) { location ->
                            // One provider may answer "nothing"; wait for the other.
                            if (location != null) finish(location)
                        }
                    } else {
                        // Written out in full: on Android 8 and 9 every one
                        // of these functions must exist.
                        val listener = object : LocationListener {
                            override fun onLocationChanged(location: Location) = finish(location)

                            @Deprecated("Deprecated in Java")
                            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

                            override fun onProviderEnabled(provider: String) {}

                            override fun onProviderDisabled(provider: String) {}
                        }
                        listeners.add(listener)
                        @Suppress("DEPRECATION")
                        manager.requestSingleUpdate(name, listener, Looper.getMainLooper())
                    }
                }
            } catch (e: Exception) {
                finish(null)
            }
            continuation.invokeOnCancellation {
                listeners.forEach { runCatching { manager.removeUpdates(it) } }
            }
        }
    }
    // Nothing new in time: an older position is better than none.
    return fresh ?: last
}

// Gives a function that finds the phone's position and hands it over as
// "latitude,longitude". It asks for the location permission when needed.
//   onLocation: the position was found.
//   onProgress: a short line to show while searching (null = done).
//   onError:    why it did not work, in words for the user.
@Composable
fun rememberLocationSender(
    onLocation: (String) -> Unit,
    onProgress: (String?) -> Unit,
    onError: (String) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val find: () -> Unit = {
        onProgress("Đang tìm vị trí của bạn…")
        scope.launch {
            val location = attempt { currentLocation(context) }.getOrNull()
            onProgress(null)
            if (location == null) {
                onError("Không tìm được vị trí. Hãy bật Vị trí (GPS) trên điện thoại rồi thử lại.")
            } else {
                onLocation(String.format(Locale.US, "%.6f,%.6f", location.latitude, location.longitude))
            }
        }
    }
    val askPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result.values.any { it }) {
            find()
        } else {
            onError("Cần quyền Vị trí để gửi vị trí. Bạn có thể bật trong Cài đặt của điện thoại.")
        }
    }
    return {
        val granted = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
        if (granted) {
            find()
        } else {
            askPermission.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            )
        }
    }
}

// Opens the position in the phone's maps app (or in the browser).
private fun openMap(context: Context, coordinates: String): Boolean {
    val parts = coordinates.split(",")
    val lat = parts.getOrNull(0)?.trim()?.toDoubleOrNull() ?: return false
    val lng = parts.getOrNull(1)?.trim()?.toDoubleOrNull() ?: return false
    val place = String.format(Locale.US, "%.6f,%.6f", lat, lng)
    val attempts = listOf(
        "geo:$place?q=$place(${Uri.encode("Vị trí")})",
        "https://www.google.com/maps/search/?api=1&query=$place",
    )
    for (address in attempts) {
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(address)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return true
        } catch (e: Exception) {
            // No app for this kind of link: try the next one.
        }
    }
    return false
}

// What a location message looks like inside its bubble. Tap to open the map.
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LocationCard(coordinates: String?, textColor: Color, onLongPress: () -> Unit = {}) {
    val context = LocalContext.current
    var note by remember { mutableStateOf<String?>(null) }
    Row(
        modifier = Modifier
            .widthIn(max = 280.dp)
            .combinedClickable(
                onLongClick = onLongPress,
                onClick = {
                    note = if (coordinates != null && openMap(context, coordinates)) {
                        null
                    } else {
                        "Không mở được bản đồ trên điện thoại này."
                    }
                },
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("📍", fontSize = 30.sp)
        Spacer(Modifier.width(10.dp))
        Column {
            Text("Vị trí", color = textColor, fontWeight = FontWeight.SemiBold)
            Text(
                coordinates?.replace(",", ", ") ?: "",
                color = textColor.copy(alpha = 0.8f),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                note ?: "Chạm để mở bản đồ",
                color = textColor.copy(alpha = 0.8f),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

// =====================================================================
// Contact card
// A contact message carries the person's user id in its extra data and
// "👤 Danh thiếp: <name>" as its text.
// =====================================================================

const val CONTACT_PREFIX = "👤 Danh thiếp: "

fun contactText(person: Profile): String = CONTACT_PREFIX + person.displayName

// What a contact card looks like inside its bubble. Tap to open a chat
// with that person (there one can also send a friend request).
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ContactCard(
    userId: String?,
    text: String,
    textColor: Color,
    onOpen: () -> Unit,
    onLongPress: () -> Unit = {},
) {
    // The picture and the username are looked up; the name is in the text.
    val person by produceState<Profile?>(initialValue = null, userId) {
        if (userId != null) value = attempt { ChatRepository.loadProfile(userId) }.getOrNull()
    }
    val name = person?.displayName ?: text.removePrefix(CONTACT_PREFIX).ifBlank { "Người dùng MayChat" }
    Row(
        modifier = Modifier
            .widthIn(max = 280.dp)
            .combinedClickable(onLongClick = onLongPress, onClick = onOpen)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(name = name, online = false, size = 44.dp, avatarPath = person?.avatarPath)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                "Danh thiếp",
                color = textColor.copy(alpha = 0.8f),
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                name,
                color = textColor,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                person?.let { "@${it.username} · Chạm để nhắn tin" } ?: "Chạm để nhắn tin",
                color = textColor.copy(alpha = 0.8f),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// Window to choose whose card to send: one of my friends.
@Composable
fun ContactPickerDialog(friends: List<Profile>, onPick: (Profile) -> Unit, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Gửi danh thiếp của ai?") },
        text = {
            if (friends.isEmpty()) {
                Text("Bạn chưa có bạn bè nào để gửi danh thiếp.")
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
                    items(friends, key = { it.id }) { person ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(person) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Avatar(
                                name = person.displayName,
                                online = false,
                                size = 40.dp,
                                avatarPath = person.avatarPath,
                            )
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(person.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "@${person.username}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Đóng") } },
    )
}

// =====================================================================
// Albums: several pictures in one message, shown as a grid
// =====================================================================

// One square picture of an album.
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumTile(path: String, size: Dp, onOpen: () -> Unit, onLongPress: () -> Unit) {
    val bitmap by produceState(initialValue = com.maychat.app.data.MediaCache.cachedBitmap(path), path) {
        if (value == null) value = attempt { com.maychat.app.data.MediaCache.bitmap(path) }.getOrNull()
    }
    val picture = bitmap
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(onLongClick = onLongPress, onClick = onOpen),
        contentAlignment = Alignment.Center,
    ) {
        if (picture != null) {
            Image(
                bitmap = picture.asImageBitmap(),
                contentDescription = "Ảnh",
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text("…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// The pictures of an album: two per row for 2 or 4 pictures, otherwise
// three per row. Tap one to see it on the whole screen.
@Composable
fun AlbumGrid(paths: List<String>, onOpen: (String) -> Unit, onLongPress: () -> Unit) {
    val perRow = if (paths.size == 2 || paths.size == 4) 2 else 3
    val tile = if (perRow == 2) 118.dp else 78.dp
    Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(3.dp)) {
        paths.chunked(perRow).forEach { row ->
            Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(3.dp)) {
                row.forEach { path ->
                    AlbumTile(path = path, size = tile, onOpen = { onOpen(path) }, onLongPress = onLongPress)
                }
            }
        }
    }
}

// Asked after pictures were chosen: send them, in normal or in high (HD)
// quality. Several pictures go as one album.
@Composable
fun SendPhotosDialog(
    count: Int,
    hd: Boolean,
    onHdChange: (Boolean) -> Unit,
    onSend: () -> Unit,
    onClose: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (count == 1) "Gửi 1 ảnh?" else "Gửi $count ảnh thành một album?") },
        text = {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onHdChange(!hd) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.Checkbox(checked = hd, onCheckedChange = onHdChange)
                Spacer(Modifier.width(4.dp))
                Column {
                    Text("Chất lượng cao (HD)")
                    Text(
                        "Ảnh nét hơn, nặng hơn khoảng 3-4 lần và gửi lâu hơn.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onSend) { Text("Gửi") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Hủy") } },
    )
}

// Shrinks the chosen pictures for sending: normal quality (longest side
// 1280) or HD (longest side 2560, less compression). Pictures that cannot
// be read are left out.
suspend fun preparePhotos(context: Context, uris: List<Uri>, hd: Boolean): List<ByteArray> =
    uris.mapNotNull { uri ->
        if (hd) compressImage(context, uri, maxSide = 2560, quality = 92) else compressImage(context, uri)
    }

// =====================================================================
// The small picture inside a quote ("replying to a photo")
// =====================================================================

// A small square picture from the chat storage.
@Composable
fun SmallPicture(path: String, size: Dp) {
    val bitmap by produceState(initialValue = com.maychat.app.data.MediaCache.cachedBitmap(path), path) {
        if (value == null) value = attempt { com.maychat.app.data.MediaCache.bitmap(path) }.getOrNull()
    }
    val picture = bitmap
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (picture != null) {
            Image(
                bitmap = picture.asImageBitmap(),
                contentDescription = "Ảnh được trả lời",
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

// What a quote shows on its left when the quoted message is a picture, an
// album or a sticker; nothing for other messages.
@Composable
fun QuoteThumb(imagePath: String?, stickerCode: String?, size: Dp = 40.dp) {
    if (imagePath != null) {
        SmallPicture(path = imagePath, size = size)
        Spacer(Modifier.width(8.dp))
    } else if (stickerCode != null) {
        StickerImage(code = stickerCode, fallback = stickerEmoji(stickerCode), size = size)
        Spacer(Modifier.width(8.dp))
    }
}
