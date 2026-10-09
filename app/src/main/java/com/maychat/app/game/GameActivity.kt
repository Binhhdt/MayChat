package com.maychat.app.game

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewAssetLoader
import com.maychat.app.data.ChatRepository
import com.maychat.app.data.attempt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.json.JSONObject
import java.io.ByteArrayOutputStream

// =====================================================================
// The farm game (tab "Game"), in its own screen, always sideways.
//
// The game is a web page packaged in the app (assets/game/: farm.html,
// hoithao.html and the pictures in img/), so it opens without
// downloading anything. The page asks the app for everything that needs
// the account through window.MayChatGame:
//   call(id, method, args)  -> the app answers with window.__mc.done(id, answer)
//   setRipeAlarm(ms)        -> a notification when the next crop is ripe
//   close()                 -> back to MayChat
// The farm itself is kept on the server (supabase_migration_31_farm.sql).
// =====================================================================
class GameActivity : ComponentActivity() {

    companion object {
        private const val PREFS = "maychat_game"
        private const val PAGE = "https://appassets.androidplatform.net/assets/game/farm.html"

        // "Mới" on the Game tab until the game was opened once.
        fun isNew(context: Context): Boolean =
            !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("opened", false)

        fun open(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("opened", true).apply()
            context.startActivity(Intent(context, GameActivity::class.java))
        }
    }

    private var web: WebView? = null
    private val json = Json { ignoreUnknownKeys = true }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Only for a logged-in account.
        if (ChatRepository.currentUserId() == null) {
            finish()
            return
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideBars()

        // The packaged files are served as https://appassets.androidplatform.net/assets/...
        // so the farm page and the Hội thao page inside it are one website.
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        val view = WebView(this)
        view.setBackgroundColor(0xFFCDEFFC.toInt())
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true            // Hội thao remembers its settings
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
        }
        view.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)

            // The game never leaves its own pages.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.url.host != "appassets.androidplatform.net"
        }
        view.addJavascriptInterface(Bridge(), "MayChatGame")
        setContentView(view)
        web = view

        // Back: first closes what is open in the game (a sheet, Hội thao), then leaves.
        onBackPressedDispatcher.addCallback(this) {
            val page = web
            if (page == null) {
                finish()
            } else {
                page.evaluateJavascript("(window.__mc && window.__mc.back) ? window.__mc.back() : false") { answer ->
                    if (answer != "true") finish()
                }
            }
        }
        view.loadUrl(PAGE)
    }

    private fun hideBars() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideBars()
    }

    // Not on screen: the game stops drawing (no battery used).
    override fun onPause() {
        web?.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        web?.onResume()
        // Time has passed: the page asks the server for the farm again.
        web?.evaluateJavascript("window.__mc && window.__mc.resume && window.__mc.resume()", null)
    }

    override fun onDestroy() {
        web?.apply {
            removeJavascriptInterface("MayChatGame")
            destroy()
        }
        web = null
        super.onDestroy()
    }

    private inner class Bridge {
        // Called by the page on a background thread; answers on the screen's thread.
        @JavascriptInterface
        fun call(id: Int, method: String, args: String) {
            lifecycleScope.launch {
                val answer = answer(method, args)
                web?.evaluateJavascript("window.__mc && window.__mc.done($id, ${JSONObject.quote(answer)})", null)
            }
        }

        @JavascriptInterface
        fun setRipeAlarm(ms: String) {
            FarmAlarm.set(applicationContext, ms.toLongOrNull() ?: 0L)
        }

        @JavascriptInterface
        fun close() {
            runOnUiThread { finish() }
        }
    }

    // {"ok":true,"data":...} or {"ok":false,"error":"..."}
    private suspend fun answer(method: String, args: String): String {
        val result = attempt {
            if (method == "profile") {
                profile()
            } else {
                val given = runCatching { json.parseToJsonElement(args).jsonObject }.getOrNull() ?: JsonObject(emptyMap())
                // {"name": ...} from the page -> p_name for the SQL function
                val params = buildJsonObject { given.forEach { (key, value) -> put("p_$key", value) } }
                ChatRepository.farmCall(method, params)
            }
        }
        return result.fold(
            onSuccess = { data -> "{\"ok\":true,\"data\":${data.ifBlank { "null" }}}" },
            onFailure = { e -> "{\"ok\":false,\"error\":${JSONObject.quote(e.message ?: "error")}}" },
        )
    }

    // My name and avatar (small, as a picture the page can show directly).
    private suspend fun profile(): String {
        val me = ChatRepository.currentUserId() ?: return "null"
        val profile = attempt { ChatRepository.loadProfile(me) }.getOrNull()
        val avatar = profile?.avatarPath?.takeIf { it.isNotBlank() }?.let { path ->
            attempt { smallPicture(ChatRepository.downloadAvatar(path)) }.getOrNull()
        }
        return buildJsonObject {
            put("name", profile?.displayName ?: "")
            put("avatar", avatar)
        }.toString()
    }

    private suspend fun smallPicture(bytes: ByteArray): String? = withContext(Dispatchers.Default) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 128 && bounds.outHeight / (sample * 2) >= 128) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return@withContext null
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
        bitmap.recycle()
        "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
