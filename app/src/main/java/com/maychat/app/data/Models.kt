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
