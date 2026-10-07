package com.maychat.app.ui.chat

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

// =====================================================================
// Link preview: when a text message contains a web address, the title,
// short description and picture of that page are shown under the text.
//
// The page is fetched by THIS phone, straight from the website (only
// "https" addresses), the first time the message is shown; the result is
// kept while the app runs. Nothing is sent to the MayChat server.
// =====================================================================

class LinkInfo(val url: String, val site: String, val title: String, val description: String, val image: ImageBitmap?)

private val URL_PATTERN = Regex("https://[^\\s<>\"']+", RegexOption.IGNORE_CASE)

// The first https address in a text, or null.
fun firstLink(text: String): String? =
    URL_PATTERN.find(text)?.value?.trimEnd('.', ',', ')', ']', '!', '?', ';', ':')

// Opens an address in the phone's browser. False when there is none.
fun openLink(context: Context, url: String): Boolean =
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Exception) {
        false
    }

// Results by address. A page without anything to show is remembered too
// (as EMPTY), so it is not fetched again and again.
private val EMPTY = LinkInfo("", "", "", "", null)
private val previews = LruCache<String, LinkInfo>(60)

private fun open(address: String): HttpURLConnection {
    val connection = URL(address).openConnection() as HttpURLConnection
    connection.connectTimeout = 6000
    connection.readTimeout = 8000
    connection.instanceFollowRedirects = true
    // Many sites only give their preview data to something that looks like a browser.
    connection.setRequestProperty(
        "User-Agent",
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36",
    )
    connection.setRequestProperty("Accept-Language", "vi,en;q=0.8")
    return connection
}

// Reads at most "limit" bytes of an answer.
private fun readLimited(connection: HttpURLConnection, limit: Int): ByteArray {
    connection.inputStream.use { input ->
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (out.size() < limit) {
            val count = input.read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (count <= 0) break
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }
}

// The content of <meta property="og:title" content="..."> and the like.
private fun meta(html: String, name: String): String? {
    val quoted = Regex.escape(name)
    val patterns = listOf(
        Regex("<meta[^>]+(?:property|name)=[\"']$quoted[\"'][^>]*content=[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE),
        Regex("<meta[^>]+content=[\"']([^\"']*)[\"'][^>]*(?:property|name)=[\"']$quoted[\"']", RegexOption.IGNORE_CASE),
    )
    for (pattern in patterns) {
        val found = pattern.find(html)?.groupValues?.getOrNull(1)?.trim()
        if (!found.isNullOrEmpty()) return found
    }
    return null
}

// "&amp;" and friends back into normal characters.
private fun plain(text: String): String =
    text.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
        .replace(Regex("\\s+"), " ").trim()

private suspend fun fetchPreview(url: String): LinkInfo = withContext(Dispatchers.IO) {
    try {
        val connection = open(url)
        val type = connection.contentType ?: ""
        // Only web pages have preview data (not a PDF, a video file...).
        if (!type.contains("html", ignoreCase = true)) {
            connection.disconnect()
            return@withContext EMPTY
        }
        val encoding = Regex("charset=([\\w-]+)", RegexOption.IGNORE_CASE).find(type)?.groupValues?.get(1)
        val bytes = readLimited(connection, 300_000)
        val finalUrl = connection.url?.toString() ?: url
        connection.disconnect()
        val html = runCatching {
            String(bytes, java.nio.charset.Charset.forName(encoding ?: "UTF-8"))
        }.getOrElse { String(bytes) }

        val title = plain(
            meta(html, "og:title")
                ?: Regex("<title[^>]*>([^<]*)</title>", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
                ?: "",
        ).take(140)
        val description = plain(meta(html, "og:description") ?: meta(html, "description") ?: "").take(200)
        val site = runCatching { URL(finalUrl).host.removePrefix("www.") }.getOrDefault("")
        if (title.isEmpty() && description.isEmpty()) return@withContext EMPTY

        // The picture of the page, at most 1.5 MB, shown small.
        val imageAddress = meta(html, "og:image")?.let { plain(it) }?.let { address ->
            runCatching { URL(URL(finalUrl), address).toString() }.getOrNull()
        }?.takeIf { it.startsWith("https://") }
        val image = imageAddress?.let { address ->
            runCatching {
                val picture = open(address)
                val data = readLimited(picture, 1_500_000)
                picture.disconnect()
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
                var sample = 1
                while (bounds.outWidth / sample > 900) sample *= 2
                BitmapFactory.decodeByteArray(
                    data, 0, data.size,
                    BitmapFactory.Options().apply { inSampleSize = sample },
                )?.asImageBitmap()
            }.getOrNull()
        }
        LinkInfo(finalUrl, site, title, description, image)
    } catch (e: Exception) {
        EMPTY
    } catch (e: OutOfMemoryError) {
        EMPTY
    }
}

// The card under a text message that contains a link. Draws nothing while
// loading and when the page has no preview data. Tap to open the page.
@Composable
fun LinkPreviewCard(url: String, textColor: Color) {
    val context = LocalContext.current
    val info by produceState(initialValue = previews.get(url), url) {
        if (value == null) {
            val fetched = fetchPreview(url)
            previews.put(url, fetched)
            value = fetched
        }
    }
    val shown = info ?: return
    if (shown === EMPTY) return

    Spacer(Modifier.height(6.dp))
    Surface(
        color = textColor.copy(alpha = 0.12f),
        contentColor = textColor,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().clickable { openLink(context, url) },
    ) {
        Column {
            shown.image?.let { picture ->
                Image(
                    bitmap = picture,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 140.dp)
                        .clip(RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp)),
                )
            }
            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                if (shown.site.isNotEmpty()) {
                    Text(shown.site, style = MaterialTheme.typography.labelSmall, color = textColor.copy(alpha = 0.75f))
                }
                if (shown.title.isNotEmpty()) {
                    Text(
                        shown.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (shown.description.isNotEmpty()) {
                    Text(
                        shown.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = textColor.copy(alpha = 0.85f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
