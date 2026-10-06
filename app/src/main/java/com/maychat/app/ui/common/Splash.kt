package com.maychat.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maychat.app.R

private val SplashTeal = Color(0xFF0B6E6E)
private val SplashAmber = Color(0xFFF2A93B)
private val SplashSoftText = Color(0xFFD6F0EE)

// Shown for the short moment the app needs to start up. It only shows the
// brand; it does not mention what the app is doing in the background.
@Composable
fun SplashScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SplashTeal)
            .safeDrawingPadding()
            .padding(horizontal = 32.dp, vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Spacer(Modifier.height(24.dp))

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // Logo: a teal chat bubble on a white rounded tile.
            Box(
                modifier = Modifier
                    .size(124.dp)
                    .clip(RoundedCornerShape(36.dp))
                    .background(Color.White),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_notification),
                    contentDescription = null,
                    tint = SplashTeal,
                    modifier = Modifier.size(76.dp),
                )
            }
            Spacer(Modifier.height(28.dp))
            Text(
                "MayChat",
                color = Color.White,
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "Nhắn tin và gọi điện qua Wi-Fi hoặc Internet",
                color = SplashSoftText,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 260.dp),
            )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            LinearProgressIndicator(
                modifier = Modifier.width(120.dp).clip(RoundedCornerShape(2.dp)),
                color = SplashAmber,
                trackColor = Color.White.copy(alpha = 0.25f),
            )
            Spacer(Modifier.height(16.dp))
            Text("Đang mở…", color = SplashSoftText, style = MaterialTheme.typography.bodySmall)
        }
    }
}
