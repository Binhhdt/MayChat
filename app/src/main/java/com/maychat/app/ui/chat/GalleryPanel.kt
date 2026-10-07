package com.maychat.app.ui.chat

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.maychat.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// =====================================================================
// The photo panel under the text box (like Zalo): the newest pictures of
// the phone as a grid. Tap pictures to tick them (they are numbered in the
// order they will be sent), choose "HD" or not, press send.
//
// It needs the permission to read the phone's pictures. Without it the
// chat screens fall back to the phone's own photo picker.
// =====================================================================

// The permissions that let the app list the phone's pictures.
fun galleryPermissions(): Array<String> = when {
    // Android 14+: the user may also give only some pictures.
    Build.VERSION.SDK_INT >= 34 -> arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
    )
    Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

// True when all pictures, or (Android 14+) at least the chosen ones, can be read.
fun canReadGallery(context: Context): Boolean =
    galleryPermissions().any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

// The newest pictures on the phone (at most 300), newest first.
private suspend fun loadGallery(context: Context): List<Uri> = withContext(Dispatchers.IO) {
    val found = ArrayList<Uri>()
    try {
        val collection = if (Build.VERSION.SDK_INT >= 29) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.Images.Media._ID),
            null,
            null,
            MediaStore.Images.Media.DATE_ADDED + " DESC",
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (cursor.moveToNext() && found.size < 300) {
                found.add(ContentUris.withAppendedId(collection, cursor.getLong(idColumn)))
            }
        }
    } catch (e: Exception) {
        // Not allowed or not available: an empty list.
    }
    found
}

private val galleryThumbs = LruCache<String, ImageBitmap>(120)

// A small version of one picture of the phone.
private suspend fun loadThumb(context: Context, uri: Uri): ImageBitmap? {
    val key = uri.toString()
    galleryThumbs.get(key)?.let { return it }
    return withContext(Dispatchers.IO) {
        try {
            val bitmap: Bitmap? = if (Build.VERSION.SDK_INT >= 29) {
                context.contentResolver.loadThumbnail(uri, Size(256, 256), null)
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Thumbnails.getThumbnail(
                    context.contentResolver,
                    ContentUris.parseId(uri),
                    MediaStore.Images.Thumbnails.MINI_KIND,
                    null,
                )
            }
            bitmap?.asImageBitmap()?.also { galleryThumbs.put(key, it) }
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }
}

// The panel itself.
//   hd / onHdChange: the "HD" switch.
//   onCamera:        the "Chụp ảnh" tile was tapped.
//   onSend:          the ticked pictures, in the order they were ticked.
//   onOpenPicker:    "Xem tất cả": open the phone's own photo picker.
//   onClose:         the "‹" button.
@Composable
fun GalleryPanel(
    hd: Boolean,
    onHdChange: (Boolean) -> Unit,
    onCamera: () -> Unit,
    onSend: (List<Uri>) -> Unit,
    onOpenPicker: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    var photos by remember { mutableStateOf<List<Uri>?>(null) }
    var picked by remember { mutableStateOf<List<Uri>>(emptyList()) }
    LaunchedEffect(Unit) { photos = loadGallery(context) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(340.dp)
            .background(MaterialTheme.colorScheme.surface),
    ) {
        // Bar: close, HD, how many are ticked, send.
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onClose) { Text("‹", style = MaterialTheme.typography.headlineSmall) }
            Spacer(Modifier.weight(1f))
            Text(
                if (hd) "HD ✓" else "HD",
                fontWeight = FontWeight.SemiBold,
                color = if (hd) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (hd) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { onHdChange(!hd) }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            )
            Spacer(Modifier.weight(1f))
            if (picked.isNotEmpty()) {
                Text(
                    "${picked.size} ảnh",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
            }
            FilledIconButton(
                onClick = {
                    val chosen = picked
                    picked = emptyList()
                    onSend(chosen)
                },
                enabled = picked.isNotEmpty(),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_send),
                    contentDescription = "Gửi ảnh đã chọn",
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(4.dp))
        }

        val list = photos
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            // First tile: take a photo now.
            item(key = "camera") {
                GalleryActionTile(icon = R.drawable.ic_camera, label = "Chụp ảnh", onClick = onCamera)
            }
            if (list != null) {
                items(list, key = { it.toString() }) { uri ->
                    val order = picked.indexOf(uri)
                    GalleryTile(
                        uri = uri,
                        order = order,
                        onClick = {
                            picked = when {
                                order >= 0 -> picked - uri
                                // At most 10 pictures in one go.
                                picked.size >= 10 -> picked
                                else -> picked + uri
                            }
                        },
                    )
                }
            }
            // Last tile: everything else (albums, cloud...) through the
            // phone's own picker. Also the way in when the list is empty.
            item(key = "more") {
                GalleryActionTile(
                    icon = R.drawable.ic_image,
                    label = if (list != null && list.isEmpty()) "Chọn ảnh" else "Xem tất cả",
                    onClick = onOpenPicker,
                )
            }
        }
    }
}

@Composable
private fun GalleryActionTile(icon: Int, label: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

// One picture of the phone. order: -1 when not ticked, otherwise its place
// (0 = first) among the ticked ones.
@Composable
private fun GalleryTile(uri: Uri, order: Int, onClick: () -> Unit) {
    val context = LocalContext.current
    val thumb by produceState(initialValue = galleryThumbs.get(uri.toString()), uri) {
        if (value == null) value = loadThumb(context, uri)
    }
    val picture = thumb
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
    ) {
        if (picture != null) {
            Image(
                bitmap = picture,
                contentDescription = "Ảnh trong máy",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        // The tick circle in the corner, with the number when ticked.
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp)
                .size(24.dp)
                .clip(CircleShape)
                .background(if (order >= 0) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.25f))
                .border(1.5.dp, Color.White, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (order >= 0) {
                Text(
                    (order + 1).toString(),
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

// Gives a function that opens the photo panel: it asks for the permission
// first when needed; when the permission is refused, onDenied is called
// (the chat screens then open the phone's own photo picker instead).
@Composable
fun rememberGalleryOpener(onGranted: () -> Unit, onDenied: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.any { it } || canReadGallery(context)) onGranted() else onDenied()
    }
    return {
        if (canReadGallery(context)) onGranted() else ask.launch(galleryPermissions())
    }
}
