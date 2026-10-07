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
    // Chat background shared by both members (null = none).
    val wallpaper: String? = null,
)

@Serializable
data class Message(
    val id: String,
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("sender_id") val senderId: String,
    val content: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("read_at") val readAt: String? = null,
    // When the receiver's app got the message (null = not yet).
    @SerialName("delivered_at") val deliveredAt: String? = null,
    // "text", "image" or "voice"
    val kind: String = "text",
    // Where the image/voice file is in Supabase Storage (null for text).
    @SerialName("media_path") val mediaPath: String? = null,
    @SerialName("duration_ms") val durationMs: Int? = null,
    // Set when the sender took the message back.
    @SerialName("recalled_at") val recalledAt: String? = null,
    // Reply (quote): the message being answered, a short copy of its text,
    // and who wrote it. All null for a normal message.
    @SerialName("reply_to_id") val replyToId: String? = null,
    @SerialName("reply_preview") val replyPreview: String? = null,
    @SerialName("reply_sender_id") val replySenderId: String? = null,
    // For a sent file: its original name and its size in bytes.
    @SerialName("file_name") val fileName: String? = null,
    @SerialName("file_size") val fileSize: Int? = null,
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

// What the app sends when creating a text message that answers another one.
@Serializable
data class NewReplyMessage(
    @SerialName("conversation_id") val conversationId: String,
    val content: String,
    @SerialName("reply_to_id") val replyToId: String,
    @SerialName("reply_preview") val replyPreview: String,
    @SerialName("reply_sender_id") val replySenderId: String,
)

// One reaction: this user put this emoji on this message.
@Serializable
data class Reaction(
    @SerialName("message_id") val messageId: String,
    @SerialName("user_id") val userId: String,
    val emoji: String,
)

// What the app sends for a picture or voice message that answers another one.
@Serializable
data class NewMediaReplyMessage(
    @SerialName("conversation_id") val conversationId: String,
    val content: String,
    val kind: String,
    @SerialName("media_path") val mediaPath: String,
    @SerialName("duration_ms") val durationMs: Int?,
    @SerialName("reply_to_id") val replyToId: String,
    @SerialName("reply_preview") val replyPreview: String,
    @SerialName("reply_sender_id") val replySenderId: String,
)

// The message pinned at the top of a conversation.
@Serializable
data class PinnedMessage(
    @SerialName("message_id") val messageId: String,
    val content: String,
    @SerialName("created_at") val createdAt: String,
    val kind: String = "text",
)

// What the app sends when creating a file message.
@Serializable
data class NewFileMessage(
    @SerialName("conversation_id") val conversationId: String,
    val content: String,
    val kind: String,
    @SerialName("media_path") val mediaPath: String,
    @SerialName("file_name") val fileName: String,
    @SerialName("file_size") val fileSize: Int,
)

// My own settings for one conversation (see supabase_migration_16).
@Serializable
data class ConversationPref(
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("pinned_at") val pinnedAt: String? = null,
    val muted: Boolean = false,
    @SerialName("cleared_at") val clearedAt: String? = null,
)

// One row of the "user_settings" table.
@Serializable
data class UserSettings(
    @SerialName("mute_messages") val muteMessages: Boolean = false,
)

// One call in the call history.
@Serializable
data class CallLog(
    val id: String,
    @SerialName("caller_id") val callerId: String,
    @SerialName("callee_id") val calleeId: String,
    @SerialName("started_at") val startedAt: String? = null,
    val status: String = "ringing",
    @SerialName("duration_s") val durationS: Int = 0,
)

// A call of the history together with the other person's profile.
data class CallItem(val call: CallLog, val other: Profile, val outgoing: Boolean)

// One relay (TURN) server for calls, from the "call_servers" table.
@Serializable
data class CallServer(
    val urls: String,
    val username: String = "",
    val credential: String = "",
)

// ---------------------------------------------------------------------
// Group chats (see supabase_migration_21_groups.sql)
// ---------------------------------------------------------------------

@Serializable
data class Group(
    val id: String,
    val name: String,
    @SerialName("owner_id") val ownerId: String,          // the group leader
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("last_message_text") val lastMessageText: String? = null,
    @SerialName("last_message_at") val lastMessageAt: String? = null,
    @SerialName("last_sender_id") val lastSenderId: String? = null,
    // Background shared by all members (null = none), see migration 22.
    val wallpaper: String? = null,
    // Picture of the group, a file in the "avatars" storage (migration 24).
    @SerialName("avatar_path") val avatarPath: String? = null,
)

@Serializable
data class GroupMember(
    @SerialName("group_id") val groupId: String,
    @SerialName("user_id") val userId: String,
    @SerialName("joined_at") val joinedAt: String? = null,
    // Up to when this member has read the group (for "Đã xem").
    @SerialName("last_read_at") val lastReadAt: String? = null,
    // "member" or "deputy" (migration 24). The leader is Group.ownerId.
    val role: String = "member",
)

@Serializable
data class GroupMessage(
    val id: String,
    @SerialName("group_id") val groupId: String,
    @SerialName("sender_id") val senderId: String,
    val content: String,
    val kind: String = "text",          // text, image, voice, file or system
    @SerialName("media_path") val mediaPath: String? = null,
    @SerialName("duration_ms") val durationMs: Int? = null,
    @SerialName("file_name") val fileName: String? = null,
    @SerialName("file_size") val fileSize: Int? = null,
    @SerialName("created_at") val createdAt: String? = null,
    // From migration 22: taken back by the sender, and the quoted message
    // when this one is a reply. All null for a normal message.
    @SerialName("recalled_at") val recalledAt: String? = null,
    @SerialName("reply_to_id") val replyToId: String? = null,
    @SerialName("reply_preview") val replyPreview: String? = null,
    @SerialName("reply_sender_id") val replySenderId: String? = null,
)

// What the app sends when creating a group message.
@Serializable
data class NewGroupMessage(
    @SerialName("group_id") val groupId: String,
    val content: String,
    val kind: String = "text",
    @SerialName("media_path") val mediaPath: String? = null,
    @SerialName("duration_ms") val durationMs: Int? = null,
    @SerialName("file_name") val fileName: String? = null,
    @SerialName("file_size") val fileSize: Int? = null,
)

// One row returned by the database function group_unread_counts().
@Serializable
data class GroupUnread(
    @SerialName("group_id") val groupId: String,
    val unread: Int,
)

// What the app sends for a group message that answers another one.
@Serializable
data class NewGroupReplyMessage(
    @SerialName("group_id") val groupId: String,
    val content: String,
    val kind: String = "text",
    @SerialName("media_path") val mediaPath: String? = null,
    @SerialName("duration_ms") val durationMs: Int? = null,
    @SerialName("reply_to_id") val replyToId: String,
    @SerialName("reply_preview") val replyPreview: String,
    @SerialName("reply_sender_id") val replySenderId: String,
)

// My own settings for one group (see supabase_migration_22).
@Serializable
data class GroupPref(
    @SerialName("group_id") val groupId: String,
    @SerialName("pinned_at") val pinnedAt: String? = null,
    val muted: Boolean = false,
    @SerialName("cleared_at") val clearedAt: String? = null,
)
