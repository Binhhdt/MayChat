package com.maychat.app.call

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.maychat.app.ui.common.Avatar
import kotlinx.coroutines.delay

private val AcceptGreen = Color(0xFF2EBD59)

// Full-screen call screen, drawn on top of whatever screen is open.
// Shown for outgoing, incoming and running calls.
@Composable
fun CallScreen(call: CallUi) {
    val context = LocalContext.current
    val view = LocalView.current
    var micDenied by remember { mutableStateOf(false) }

    // The Back button must not close the call screen by accident.
    BackHandler(enabled = true) { }

    // Keep the display on while the call screen is showing.
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    // Accepting needs the microphone permission.
    val askMicrophone = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) CallManager.accept() else micDenied = true
    }

    // Call length, counted while connected.
    var elapsedMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(call.phase, call.connectedAtMs) {
        while (call.phase == CallPhase.CONNECTED) {
            elapsedMs = System.currentTimeMillis() - call.connectedAtMs
            delay(500)
        }
    }

    val statusText = when (call.phase) {
        CallPhase.CONNECTED -> formatCallTime(elapsedMs)
        else -> call.message
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(48.dp))
            Avatar(name = call.peer.displayName, online = false, size = 112.dp)
            Spacer(Modifier.height(20.dp))
            Text(
                call.peer.displayName,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                statusText,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            if (micDenied) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Cần quyền micro để nghe máy. Bạn có thể bật trong Cài đặt của điện thoại.",
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(Modifier.weight(1f))

            when (call.phase) {
                CallPhase.INCOMING -> Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    Button(
                        onClick = { CallManager.reject() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                    ) { Text("Từ chối") }
                    Button(
                        onClick = {
                            val granted = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.RECORD_AUDIO,
                            ) == PackageManager.PERMISSION_GRANTED
                            if (granted) CallManager.accept() else askMicrophone.launch(Manifest.permission.RECORD_AUDIO)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AcceptGreen,
                            contentColor = Color.White,
                        ),
                    ) { Text("Nghe máy") }
                }

                CallPhase.OUTGOING, CallPhase.CONNECTING, CallPhase.CONNECTED -> Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        OutlinedButton(onClick = { CallManager.toggleMute() }) {
                            Text(if (CallManager.muted) "🔇 Bật mic" else "🎤 Tắt mic")
                        }
                        OutlinedButton(onClick = { CallManager.toggleSpeaker() }) {
                            Text(if (CallManager.speakerOn) "🔊 Tắt loa ngoài" else "🔈 Loa ngoài")
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                    Button(
                        onClick = { CallManager.hangUp() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                    ) { Text("Kết thúc") }
                }

                CallPhase.ENDED -> {}
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun formatCallTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val minutes = total / 60
    val seconds = total % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}
