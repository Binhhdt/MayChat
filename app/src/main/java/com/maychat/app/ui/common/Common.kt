package com.maychat.app.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.maychat.app.R
import com.maychat.app.data.MediaCache
import com.maychat.app.data.attempt
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// Full-screen spinner with an optional line of text.
@Composable
fun LoadingScreen(text: String? = null) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        if (text != null) {
            Spacer(Modifier.height(16.dp))
            Text(text, textAlign = TextAlign.Center)
        }
    }
}

// Round avatar. Shows the person's picture when they have one, otherwise
// the first letter of the name. A green dot means "online".
@Composable
fun Avatar(name: String, online: Boolean, size: Dp = 48.dp, avatarPath: String? = null) {
    val picture by produceState(
        initialValue = avatarPath?.let { MediaCache.cachedAvatar(it) },
        avatarPath,
    ) {
        value = if (avatarPath == null) {
            null
        } else {
            MediaCache.cachedAvatar(avatarPath) ?: attempt { MediaCache.avatarBitmap(avatarPath) }.getOrNull()
        }
    }
    val loaded = picture

    Box(modifier = Modifier.size(size)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            if (loaded != null) {
                Image(
                    bitmap = loaded.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    text = name.trim().take(1).uppercase().ifEmpty { "?" },
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
        if (online) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(size / 3.5f)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(2.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF2EBD59)),
            )
        }
    }
}

private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
private val dateTimeFormat = DateTimeFormatter.ofPattern("dd/MM HH:mm")

// Turns a database timestamp into local time. Shows the date too when the
// message is not from today. Returns "" if the text cannot be read.
fun formatTime(timestamp: String?): String {
    if (timestamp == null) return ""
    return runCatching {
        val local = OffsetDateTime.parse(timestamp).atZoneSameInstant(ZoneId.systemDefault())
        val today = java.time.LocalDate.now(ZoneId.systemDefault())
        if (local.toLocalDate() == today) local.format(timeFormat) else local.format(dateTimeFormat)
    }.getOrDefault("")
}

// Text shown under a name when the person is not online: how long ago they
// were last active. Falls back to plain "Không hoạt động" when that is unknown.
fun offlineLabel(lastSeenAt: String?, nowMs: Long): String {
    if (lastSeenAt == null) return "Không hoạt động"
    val seenMs = runCatching { OffsetDateTime.parse(lastSeenAt).toInstant().toEpochMilli() }.getOrNull()
        ?: return "Không hoạt động"
    val minutes = ((nowMs - seenMs) / 60_000).coerceAtLeast(0)
    return when {
        minutes < 1 -> "Không hoạt động dưới 1 phút"
        minutes < 60 -> "Không hoạt động $minutes phút"
        minutes < 60 * 24 -> "Không hoạt động ${minutes / 60} giờ"
        else -> "Không hoạt động ${minutes / (60 * 24)} ngày"
    }
}

// The calendar day (in the phone's time zone) a message belongs to.
// A message without a time yet (still sending) counts as today.
fun localDay(timestamp: String?): java.time.LocalDate {
    val zone = ZoneId.systemDefault()
    if (timestamp == null) return java.time.LocalDate.now(zone)
    return runCatching {
        OffsetDateTime.parse(timestamp).atZoneSameInstant(zone).toLocalDate()
    }.getOrDefault(java.time.LocalDate.now(zone))
}

private val dayFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy")

// Label of the line shown between messages of different days.
fun dayLabel(day: java.time.LocalDate): String {
    val today = java.time.LocalDate.now(ZoneId.systemDefault())
    return when (day) {
        today -> "Hôm nay"
        today.minusDays(1) -> "Hôm qua"
        else -> day.format(dayFormat)
    }
}

// The "back" arrow used at the top of screens.
@Composable
fun BackButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            painter = painterResource(R.drawable.ic_back),
            contentDescription = "Quay lại",
        )
    }
}
