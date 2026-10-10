package com.maychat.app.game

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// The invite card of the Hội thao in a one-to-one chat (message kind
// "game", extra = the room id). The friend taps "Vào" to join the room;
// the one who invited sees the same card without the button.
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GameInviteCard(
    text: String,
    roomId: String?,
    mine: Boolean,
    textColor: Color,
    onLongPress: () -> Unit = {},
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .widthIn(max = 290.dp)
            .combinedClickable(onLongClick = onLongPress, onClick = {})
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("🏃", fontSize = 30.sp)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f, fill = false)) {
            Text(
                "Hội thao",
                color = textColor.copy(alpha = 0.8f),
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                if (mine) "Bạn đã mời vào phòng Hội thao" else text.removePrefix("🏃").trim(),
                color = textColor,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                if (mine) "Chờ bạn bè bấm Vào" else "Đua 4 map cùng nhau, thắng để nhận vàng",
                color = textColor.copy(alpha = 0.8f),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (!mine && roomId != null) {
            Spacer(Modifier.width(10.dp))
            Button(onClick = { GameActivity.open(context, roomId) }) { Text("Vào") }
        }
    }
}
