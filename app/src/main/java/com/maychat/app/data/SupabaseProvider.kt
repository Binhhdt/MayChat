package com.maychat.app.data

import com.maychat.app.BuildConfig
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.storage.Storage

// Creates the one Supabase client used by the whole app.
// The URL and the publishable key come from the build (GitHub Secrets),
// they are not written in this file.
object SupabaseProvider {

    // False when the APK was built without the two secrets.
    val isConfigured: Boolean
        get() = BuildConfig.SUPABASE_URL.startsWith("https://") &&
            BuildConfig.SUPABASE_KEY.isNotBlank()

    val client: SupabaseClient by lazy {
        createSupabaseClient(
            supabaseUrl = BuildConfig.SUPABASE_URL,
            supabaseKey = BuildConfig.SUPABASE_KEY,
        ) {
            install(Auth)       // accounts, login session (saved on the phone)
            install(Postgrest)  // read/write database tables
            install(Realtime)   // live updates over a WebSocket
            install(Storage)    // image and voice files
        }
    }
}
