package com.maychat.app.game

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.maychat.app.data.ChatRepository
import com.maychat.app.data.attempt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val HOUSE_NAMES = listOf("Chòi tre", "Nhà gỗ", "Nhà ngói", "Nhà lầu", "Biệt thự")

// The farm on a MayChat profile page: the house (picture of its level),
// the farm's name, and "Thăm nông trại" on a friend's page. Shown only for
// me and for my friends who have a farm; nothing at all otherwise.
@Composable
fun FarmProfileCard(userId: String, isMe: Boolean) {
    val context = LocalContext.current
    val farm by produceState<Pair<String, Int>?>(initialValue = null, userId) {
        value = attempt { ChatRepository.farmProfile(userId) }.getOrNull()
    }
    val (name, level) = farm ?: return
    val lv = level.coerceIn(1, 5)
    val house by produceState<Bitmap?>(initialValue = null, lv) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.assets.open("game/img/house$lv.webp").use { BitmapFactory.decodeStream(it) } }.getOrNull()
        }
    }
    Spacer(Modifier.height(14.dp))
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            house?.let {
                Image(bitmap = it.asImageBitmap(), contentDescription = HOUSE_NAMES[lv - 1], modifier = Modifier.size(84.dp))
                Spacer(Modifier.width(10.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Nông trại",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("🏡 Nông trại $name", fontWeight = FontWeight.SemiBold)
                Text(
                    "Nhà cấp $lv · ${HOUSE_NAMES[lv - 1]}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!isMe) {
                    Spacer(Modifier.height(6.dp))
                    FilledTonalButton(onClick = { GameActivity.open(context, visitUserId = userId) }) {
                        Text("Thăm nông trại")
                    }
                }
            }
        }
    }
}
