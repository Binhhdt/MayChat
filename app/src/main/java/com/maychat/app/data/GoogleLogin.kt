package com.maychat.app.data

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import java.security.MessageDigest
import java.security.SecureRandom

// "Tiếp tục với Google": the phone's own Google account picker (Credential
// Manager). Google checks the person and hands back a signed token; the
// password never reaches MayChat. Supabase then checks the token.
object GoogleLogin {
    // The "Web application" OAuth client of the Google Cloud project
    // (MayChat Supabase). Not a secret: it only names the app to Google.
    // The client SECRET stays in Supabase only.
    const val WEB_CLIENT_ID =
        "510025127615-odq62r173v02oqmblvn6u349pnass500.apps.googleusercontent.com"

    class Cancelled : Exception("cancelled")

    // What the account picker gives: the token for Supabase, the nonce that
    // goes with it, and the name of the Google account (for the profile).
    class Picked(val idToken: String, val rawNonce: String, val name: String?, val email: String?)

    // Shows the account picker. Needs a screen (Activity) context.
    suspend fun pick(context: Context): Picked {
        // A random "nonce": Google signs its hash into the token, Supabase
        // checks it with the raw value, so a token cannot be reused.
        val rawNonce = ByteArray(24).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
        val hashedNonce = MessageDigest.getInstance("SHA-256").digest(rawNonce.toByteArray())
            .joinToString("") { "%02x".format(it) }

        val option = GetSignInWithGoogleOption.Builder(WEB_CLIENT_ID)
            .setNonce(hashedNonce)
            .build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(option)
            .build()
        val response = try {
            CredentialManager.create(context).getCredential(context, request)
        } catch (e: GetCredentialCancellationException) {
            throw Cancelled()
        } catch (e: NoCredentialException) {
            throw UserFacingException(
                "Máy này chưa có tài khoản Google. Hãy thêm tài khoản Google trong Cài đặt của điện thoại, " +
                    "hoặc đăng nhập bằng email.",
            )
        } catch (e: GetCredentialException) {
            throw UserFacingException(
                "Không mở được đăng nhập Google trên máy này. Hãy đăng nhập bằng email. (${e.type})",
            )
        }
        val credential = response.credential
        if (credential is CustomCredential &&
            credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            val google = GoogleIdTokenCredential.createFrom(credential.data)
            return Picked(google.idToken, rawNonce, google.displayName, google.id)
        }
        throw UserFacingException("Đăng nhập Google không thành công. Hãy thử lại.")
    }
}
