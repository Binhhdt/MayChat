package com.maychat.app.ui.common

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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

// Round avatar showing the first letter of the name, with a green dot when online.
@Composable
fun Avatar(name: String, online: Boolean, size: Dp = 48.dp) {
    Box(modifier = Modifier.size(size)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = name.trim().take(1).uppercase().ifEmpty { "?" },
                color = MaterialTheme.colorScheme.onPrimary,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleMedium,
            )
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
