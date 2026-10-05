package com.maychat.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.maychat.app.ui.home.HomeScreen
import com.maychat.app.ui.theme.MayChatTheme

// The single Activity of the app. Every screen is a Compose function shown inside it.
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MayChatTheme {
                HomeScreen()
            }
        }
    }
}
