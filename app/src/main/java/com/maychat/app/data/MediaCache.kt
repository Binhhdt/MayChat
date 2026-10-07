package com.maychat.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// Keeps downloaded images and voice files so each one is downloaded only
// once per app session. This saves mobile data and free-plan bandwidth.
object MediaCache {

    // Raw file bytes, at most about 12 MB in memory.
    private val bytesCache = object : LruCache<String, ByteArray>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size
    }

    // Decoded pictures, at most 40.
    private val bitmapCache = LruCache<String, Bitmap>(40)

    // Called right after I upload a file, so I never download my own file.
    fun put(path: String, bytes: ByteArray) {
        bytesCache.put(path, bytes)
    }

    fun cachedBitmap(path: String): Bitmap? = bitmapCache.get(path)

    suspend fun bytes(path: String): ByteArray {
        bytesCache.get(path)?.let { return it }
        val downloaded = ChatRepository.downloadMedia(path)
        bytesCache.put(path, downloaded)
        return downloaded
    }

    // The picture for showing inside a chat. A picture sent in HD is
    // decoded at half size here (still at least 1280 pixels), so a chat full
    // of HD pictures does not fill the phone's memory. The full-size picture
    // is only decoded by the full-screen viewer, see fullBitmap.
    suspend fun bitmap(path: String): Bitmap? {
        bitmapCache.get(path)?.let { return it }
        val data = bytes(path)
        val decoded = withContext(Dispatchers.Default) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
            BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }
        if (decoded != null) bitmapCache.put(path, decoded)
        return decoded
    }

    // The picture in its full size (for the full-screen viewer, where one
    // can zoom in). Not kept in memory: only one is on screen at a time.
    suspend fun fullBitmap(path: String): Bitmap? {
        val data = bytes(path)
        return withContext(Dispatchers.Default) {
            try {
                BitmapFactory.decodeByteArray(data, 0, data.size)
            } catch (e: OutOfMemoryError) {
                null
            }
        } ?: bitmap(path)
    }

    // Avatars live in a different bucket, so they have their own cache.
    private val avatarCache = LruCache<String, Bitmap>(80)

    fun cachedAvatar(path: String): Bitmap? = avatarCache.get(path)

    suspend fun avatarBitmap(path: String): Bitmap? {
        avatarCache.get(path)?.let { return it }
        val data = ChatRepository.downloadAvatar(path)
        val decoded = withContext(Dispatchers.Default) {
            BitmapFactory.decodeByteArray(data, 0, data.size)
        }
        if (decoded != null) avatarCache.put(path, decoded)
        return decoded
    }

    // The audio player needs a real file, so voice messages are also written
    // to the app's cache folder (Android may clear it when space is low).
    suspend fun file(context: Context, path: String): File {
        val dir = File(context.cacheDir, "media")
        val target = File(dir, path.replace('/', '_'))
        if (target.exists() && target.length() > 0) return target
        val data = bytes(path)
        withContext(Dispatchers.IO) {
            dir.mkdirs()
            target.writeBytes(data)
        }
        return target
    }
}
