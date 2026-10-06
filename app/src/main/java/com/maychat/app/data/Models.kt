package com.maychat.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// These classes mirror the tables created by supabase_schema.sql.
// @SerialName is the column name in the database.

@Serializable
data class Profile(
    val id: String,
    val username: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("last_seen_at") val lastSeenAt: String? = null,
    // Where the avatar picture is in Storage (null = no avatar).
    @SerialName("avatar_path") val avatarPath: String? = null,
)

@Serializable
data class Conversation(
    val id: String,
    @SerialName("user_a") val userA: String,
    @SerialName("user_b") val userB: String,
    @SerialName("last_message_text") val lastMessageText: String? = null,
    @SerialName("last_message_at") val lastMessageAt: String? = null,
    @SerialName("last_sender_id") val lastSenderId: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class Message(
    val id: String,
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("sender_id") val senderId: String,
    val content: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("read_at") val readAt: String? = null,
    // "text", "image" or "voice"
    val kind: String = "text",
    // Where the image/voice file is in Supabase Storage (null for text).
    @SerialName("media_path") val mediaPath: String? = null,
    @SerialName("duration_ms") val durationMs: Int? = null,
    // Set when the sender took the message back.
    @SerialName("recalled_at") val recalledAt: String? = null,
)

// What the app sends when creating a message. The database fills in the
// id, the sender and the time by itself, so the app cannot fake them.
@Serializable
data class NewMessage(
    @SerialName("conversation_id") val conversationId: String,
    val content: String,
)

// A conversation together with the profile of the other person.
data class ConversationItem(
    val conversation: Conversation,
    val other: Profile,
)

// One row of the "friendships" table: a pending request or an accepted friendship.
@Serializable
data class Friendship(
    val id: String,
    @SerialName("user_a") val userA: String,
    @SerialName("user_b") val userB: String,
    @SerialName("requested_by") val requestedBy: String,
    val status: String,
)

// One row of the "blocks" table (only rows where I am the blocker are visible).
@Serializable
data class Block(
    @SerialName("blocked_id") val blockedId: String,
)

// What the app sends when creating an image or voice message.
@Serializable
data class NewMediaMessage(
    @SerialName("conversation_id") val conversationId: String,
    val content: String,
    val kind: String,
    @SerialName("media_path") val mediaPath: String,
    @SerialName("duration_ms") val durationMs: Int?,
)

// One row returned by the database function unread_counts().
@Serializable
data class UnreadCount(
    @SerialName("conversation_id") val conversationId: String,
    val unread: Int,
)
