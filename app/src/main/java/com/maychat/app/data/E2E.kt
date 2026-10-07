package com.maychat.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.compose.runtime.mutableStateMapOf
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// =====================================================================
// End-to-end encryption of one-to-one conversations ("Mã hóa đầu cuối").
//
// Each phone makes a pair of keys the first time it is used with an
// account. The PRIVATE key stays on the phone (locked with the phone's own
// key store); the PUBLIC key is given to the server so the other person's
// phone can fetch it.
//
// For a conversation, my private key and the other person's public key
// give a secret that only our two phones can work out (ECDH). From it a
// key for that conversation is made (HKDF), and every text and every file
// is locked with it (AES-256-GCM) before it leaves the phone. The server
// stores and forwards what it cannot read.
//
// What a locked text looks like:  e2e:1:<8 letters>:<base64>
//   the 8 letters say which pair of keys locked it, so a message made for
//   an earlier phone is recognised as "cannot be read here" at once.
// A locked file starts with the four letters MCE1.
//
// Changing phone or reinstalling the app makes a new key pair: messages
// locked before that cannot be read any more (chosen on purpose: nothing
// that could open them is ever stored outside the phone).
// =====================================================================

@Serializable
private data class E2eKeyRow(
    @SerialName("user_id") val userId: String,
    @SerialName("public_key") val publicKey: String,
)

object E2E {
    const val PREFIX = "e2e:1:"
    const val UNREADABLE = "🔒 Tin nhắn mã hóa không đọc được trên máy này"
    const val UNREADABLE_OLD = "🔒 Tin nhắn được mã hóa cho thiết bị cũ, không đọc được trên máy này"
    private val FILE_MAGIC = byteArrayOf('M'.code.toByte(), 'C'.code.toByte(), 'E'.code.toByte(), '1'.code.toByte())

    private const val PREFS = "maychat_e2e"
    private const val WRAP_ALIAS = "maychat_e2e_wrap"
    private val supabase get() = SupabaseProvider.client
    private val random = SecureRandom()
    private val lock = Mutex()

    private var app: Context? = null

    fun init(context: Context) {
        app = context.applicationContext
    }

    // What is known about one conversation.
    private class Conv(
        val on: Boolean,
        val key: ByteArray?,      // null: no key (the other phone has none yet)
        val kid: String,          // which pair of keys "key" comes from
        val checkedAtMs: Long,
    )

    private val known = ConcurrentHashMap<String, Conv>()
    private val stale = ConcurrentHashMap.newKeySet<String>()

    // For the screens: conversation id -> encryption is on.
    val active = mutableStateMapOf<String, Boolean>()

    // ------------------------------------------------------------------
    // My key pair
    // ------------------------------------------------------------------

    @Volatile
    private var myPair: KeyPair? = null
    @Volatile
    private var myPairOwner: String = ""
    @Volatile
    private var publishedFor: String = ""

    private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun unb64(text: String): ByteArray = Base64.decode(text, Base64.NO_WRAP)

    // The key of the phone's own key store that locks my private key
    // while it is stored. null when this phone cannot provide one.
    private fun wrapKey(): SecretKey? = runCatching {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(WRAP_ALIAS, null) as? SecretKey) ?: run {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(
                KeyGenParameterSpec.Builder(
                    WRAP_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generator.generateKey()
        }
    }.getOrNull()

    // My key pair for this account on this phone: read from the phone, or
    // made now. null only when the phone cannot do this kind of key at all.
    private fun myKeyPair(myId: String): KeyPair? {
        myPair?.let { if (myPairOwner == myId) return it }
        val context = app ?: return null
        synchronized(this) {
            myPair?.let { if (myPairOwner == myId) return it }
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val factory = KeyFactory.getInstance("EC")

            // 1. A pair saved earlier.
            val saved = runCatching {
                val publicText = prefs.getString("pub_$myId", null) ?: return@runCatching null
                val locked = prefs.getString("priv_$myId", null) ?: return@runCatching null
                val plain = prefs.getBoolean("plain_$myId", false)
                val privateBytes = if (plain) {
                    unb64(locked)
                } else {
                    val raw = unb64(locked)
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE, wrapKey() ?: return@runCatching null, GCMParameterSpec(128, raw, 0, 12))
                    cipher.doFinal(raw, 12, raw.size - 12)
                }
                KeyPair(
                    factory.generatePublic(X509EncodedKeySpec(unb64(publicText))),
                    factory.generatePrivate(PKCS8EncodedKeySpec(privateBytes)),
                )
            }.getOrNull()
            if (saved != null) {
                myPair = saved
                myPairOwner = myId
                return saved
            }

            // 2. A new pair, saved with the private half locked.
            return runCatching {
                val generator = KeyPairGenerator.getInstance("EC")
                generator.initialize(ECGenParameterSpec("secp256r1"), random)
                val pair = generator.generateKeyPair()
                val wrap = wrapKey()
                val editor = prefs.edit().putString("pub_$myId", b64(pair.public.encoded))
                if (wrap != null) {
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.ENCRYPT_MODE, wrap)
                    val locked = cipher.iv + cipher.doFinal(pair.private.encoded)
                    editor.putString("priv_$myId", b64(locked)).putBoolean("plain_$myId", false)
                } else {
                    // No key store on this phone: kept in the app's private
                    // storage, which other apps cannot read.
                    editor.putString("priv_$myId", b64(pair.private.encoded)).putBoolean("plain_$myId", true)
                }
                editor.apply()
                myPair = pair
                myPairOwner = myId
                pair
            }.getOrNull()
        }
    }

    private fun currentUserId(): String? = runCatching { supabase.auth.currentUserOrNull()?.id }.getOrNull()

    // Called when somebody is logged in: makes sure this phone has a key
    // pair and that the server knows its public half. Quietly does nothing
    // when the server does not have migration 30 yet.
    suspend fun start(myId: String) {
        if (publishedFor == myId) return
        val pair = myKeyPair(myId) ?: return
        attempt {
            supabase.postgrest.rpc(
                "set_my_e2e_key",
                buildJsonObject { put("p_public_key", b64(pair.public.encoded)) },
            )
        }.onSuccess { publishedFor = myId }
    }

    // Signing out: forget what was worked out for the account (the key
    // pair itself stays on the phone for the next login of that account).
    fun clear() {
        known.clear()
        stale.clear()
        active.clear()
        myPair = null
        myPairOwner = ""
        publishedFor = ""
    }

    // ------------------------------------------------------------------
    // The key of a conversation
    // ------------------------------------------------------------------

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    // HKDF (RFC 5869) with SHA-256, one block of output = 32 bytes.
    private fun deriveKey(shared: ByteArray, conversationId: String): ByteArray {
        val extracted = hmac("MayChat-E2E-v1".toByteArray(), shared)
        return hmac(extracted, conversationId.toByteArray() + byteArrayOf(1))
    }

    private fun hex(bytes: ByteArray, count: Int): String =
        bytes.take(count).joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    // Which pair of public keys: the same on both phones.
    private fun pairId(a: ByteArray, b: ByteArray): String {
        val (first, second) = if (b64(a) <= b64(b)) a to b else b to a
        return hex(MessageDigest.getInstance("SHA-256").digest(first + second), 4)
    }

    private fun build(myId: String, conversationId: String, on: Boolean, peerPublic: String?): Conv {
        val now = System.currentTimeMillis()
        val pair = myKeyPair(myId)
        if (pair == null || peerPublic == null) return Conv(on, null, "", now)
        return runCatching {
            val peerBytes = unb64(peerPublic)
            val peerKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(peerBytes))
            val agreement = KeyAgreement.getInstance("ECDH")
            agreement.init(pair.private)
            agreement.doPhase(peerKey, true)
            val key = deriveKey(agreement.generateSecret(), conversationId)
            Conv(on, key, pairId(pair.public.encoded, peerBytes), now)
        }.getOrElse { Conv(on, null, "", now) }
    }

    // A change was made (or announced) in this conversation: read its
    // switch again before the next message.
    fun markStale(conversationId: String) {
        stale.add(conversationId)
    }

    // What is known about a conversation, read from the server when it is
    // not known yet, older than 5 minutes, marked stale, or force = true.
    // Without a connection: what this phone remembered from last time.
    // null when the id is not one of my conversations (a group, say).
    private suspend fun ensure(conversationId: String, force: Boolean = false): Conv? {
        val myId = currentUserId() ?: return null
        val have = known[conversationId]
        val fresh = have != null && System.currentTimeMillis() - have.checkedAtMs < 5 * 60_000
        if (have != null && fresh && !force && conversationId !in stale) return have

        return lock.withLock {
            val prefs = app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val result = attempt {
                val row = supabase.postgrest.from("conversations")
                    .select { filter { eq("id", conversationId) } }
                    .decodeList<Conversation>()
                    .firstOrNull()
                if (row == null) {
                    // Not a one-to-one conversation of mine.
                    Conv(false, null, "", System.currentTimeMillis())
                } else {
                    val peerId = if (row.userA == myId) row.userB else row.userA
                    // The other person's key matters only once encryption
                    // is, or was, used here.
                    val wasUsed = prefs?.contains("peer_${myId}_$conversationId") == true
                    val peerPublic = if (row.e2e || wasUsed || force) {
                        attempt {
                            supabase.postgrest.from("e2e_keys")
                                .select { filter { eq("user_id", peerId) } }
                                .decodeList<E2eKeyRow>()
                                .firstOrNull()?.publicKey
                        }.getOrNull() ?: prefs?.getString("peer_${myId}_$conversationId", null)
                    } else {
                        null
                    }
                    prefs?.edit()?.apply {
                        putBoolean("on_${myId}_$conversationId", row.e2e)
                        if (peerPublic != null) putString("peer_${myId}_$conversationId", peerPublic)
                    }?.apply()
                    build(myId, conversationId, row.e2e, peerPublic)
                }
            }
            val conv = result.getOrNull() ?: have ?: run {
                // No connection and nothing in memory: what was remembered.
                val on = prefs?.getBoolean("on_${myId}_$conversationId", false) == true
                val peerPublic = prefs?.getString("peer_${myId}_$conversationId", null)
                if (!on && peerPublic == null) return@withLock null
                build(myId, conversationId, on, peerPublic)
            }
            if (result.isSuccess) stale.remove(conversationId)
            known[conversationId] = conv
            if (active[conversationId] != conv.on) active[conversationId] = conv.on
            conv
        }
    }

    // Is encryption on in this conversation? (Asks the server if needed.)
    suspend fun isOn(conversationId: String, force: Boolean = false): Boolean =
        ensure(conversationId, force)?.on == true

    // The list of conversations was read: note which have encryption on.
    fun noteSwitches(conversations: List<Conversation>) {
        conversations.forEach { c ->
            if (active[c.id] != c.e2e) active[c.id] = c.e2e
            val have = known[c.id]
            if (have != null && have.on != c.e2e) stale.add(c.id)
        }
    }

    // Switches encryption on or off for a conversation (for both people).
    suspend fun setOn(conversationId: String, on: Boolean) {
        currentUserId()?.let { start(it) }
        supabase.postgrest.rpc(
            "set_conversation_e2e",
            buildJsonObject {
                put("p_conversation", conversationId)
                put("p_on", on)
            },
        )
        ensure(conversationId, force = true)
    }

    // ------------------------------------------------------------------
    // Locking and opening texts
    // ------------------------------------------------------------------

    private fun lockBytes(key: ByteArray, data: ByteArray, conversationId: String): ByteArray {
        val nonce = ByteArray(12).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(conversationId.toByteArray())
        return nonce + cipher.doFinal(data)
    }

    private fun openBytes(key: ByteArray, data: ByteArray, offset: Int, conversationId: String): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, data, offset, 12))
        cipher.updateAAD(conversationId.toByteArray())
        return cipher.doFinal(data, offset + 12, data.size - offset - 12)
    }

    // The text as it is sent: locked when encryption is on in this
    // conversation, unchanged otherwise. Throws (with a message for the
    // user) when it is on but cannot be done.
    suspend fun seal(conversationId: String, text: String, force: Boolean = false): String {
        val conv = ensure(conversationId, force) ?: return text
        if (!conv.on) return text
        val key = conv.key ?: throw UserFacingException(
            "Chưa mã hóa được: máy của người kia chưa có khóa. Hãy nhờ họ mở MayChat bản mới nhất.",
        )
        val locked = PREFIX + conv.kid + ":" + b64(lockBytes(key, text.toByteArray(), conversationId))
        if (locked.length > 4000) {
            throw UserFacingException("Tin nhắn quá dài để mã hóa. Hãy chia thành vài tin ngắn hơn.")
        }
        return locked
    }

    // Like seal(), for a short text that must stay under "limit" letters
    // once locked (a quote, a file name): it is shortened first if needed.
    suspend fun sealShort(conversationId: String, text: String, limit: Int): String {
        val conv = ensure(conversationId) ?: return text
        if (!conv.on) return text
        // Locked length = 15 + 4 * ceil((bytes + 28) / 3).
        val roomBytes = ((limit - 15) / 4) * 3 - 28
        var short = text
        while (short.isNotEmpty() && short.toByteArray().size > roomBytes) short = short.dropLast(1)
        return seal(conversationId, short)
    }

    fun isLocked(text: String?): Boolean = text != null && text.startsWith(PREFIX)

    // A text as it is shown: opened when it is locked, unchanged otherwise.
    suspend fun open(conversationId: String, text: String): String {
        if (!text.startsWith(PREFIX)) return text
        val rest = text.substring(PREFIX.length)
        val at = rest.indexOf(':')
        if (at <= 0) return UNREADABLE
        val kid = rest.substring(0, at)
        val data = runCatching { unb64(rest.substring(at + 1)) }.getOrNull() ?: return UNREADABLE
        if (data.size < 28) return UNREADABLE

        var conv = ensure(conversationId) ?: return UNREADABLE
        // Locked with another pair of keys: maybe the other person has a
        // new phone that this phone has not heard of yet.
        if (conv.key == null || conv.kid != kid) {
            if (System.currentTimeMillis() - conv.checkedAtMs > 20_000) {
                conv = ensure(conversationId, force = true) ?: return UNREADABLE
            }
        }
        val key = conv.key ?: return UNREADABLE
        if (conv.kid != kid) return UNREADABLE_OLD
        return runCatching { String(openBytes(key, data, 0, conversationId)) }.getOrDefault(UNREADABLE)
    }

    // A message as it is shown (text, quote, file name, extra data).
    suspend fun open(message: Message): Message {
        if (!isLocked(message.content) && !isLocked(message.replyPreview) &&
            !isLocked(message.fileName) && !isLocked(message.extra)
        ) {
            return message
        }
        val id = message.conversationId
        return message.copy(
            content = open(id, message.content),
            replyPreview = message.replyPreview?.let { open(id, it) },
            fileName = message.fileName?.let { open(id, it) },
            extra = message.extra?.let { open(id, it) },
        )
    }

    // ------------------------------------------------------------------
    // Locking and opening files (pictures, voice, files)
    // ------------------------------------------------------------------

    // folder: the first part of the file's path = the conversation id.
    suspend fun sealFile(folder: String, bytes: ByteArray): ByteArray {
        val conv = ensure(folder) ?: return bytes
        if (!conv.on) return bytes
        val key = conv.key ?: throw UserFacingException(
            "Chưa mã hóa được: máy của người kia chưa có khóa. Hãy nhờ họ mở MayChat bản mới nhất.",
        )
        return FILE_MAGIC + lockBytes(key, bytes, folder)
    }

    suspend fun openFile(folder: String, bytes: ByteArray): ByteArray {
        if (bytes.size < 4 + 28) return bytes
        for (i in 0 until 4) if (bytes[i] != FILE_MAGIC[i]) return bytes
        var conv = ensure(folder) ?: throw UserFacingException(UNREADABLE)
        val first = conv.key?.let { runCatching { openBytes(it, bytes, 4, folder) }.getOrNull() }
        if (first != null) return first
        // Perhaps locked with the key of a newer phone of the other person.
        if (System.currentTimeMillis() - conv.checkedAtMs > 20_000) {
            conv = ensure(folder, force = true) ?: throw UserFacingException(UNREADABLE)
            conv.key?.let { runCatching { openBytes(it, bytes, 4, folder) }.getOrNull() }?.let { return it }
        }
        throw UserFacingException("🔒 File mã hóa không mở được trên máy này.")
    }
}
