package com.maychat.app.data

import kotlinx.coroutines.CancellationException

// True when Supabase rejected a login because the email or password is wrong.
// Supabase gives the same answer for "no such account" and "wrong password".
fun Throwable.isInvalidCredentials(): Boolean {
    val text = (message ?: "").lowercase()
    return "invalid login credentials" in text || "invalid_credentials" in text
}

// Runs a backend call and returns either the result or the error,
// so screens never crash when the network disappears.
suspend fun <T> attempt(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }

// An error whose message is already written for the user (in Vietnamese).
class UserFacingException(message: String) : Exception(message)

// Turns a technical error into a message a person can understand.
fun Throwable.toUserMessage(): String {
    if (this is UserFacingException) return message ?: "Có lỗi xảy ra."
    val text = (message ?: "").lowercase()
    val className = this::class.simpleName ?: ""
    return when {
        this is java.io.IOException ||
            className.contains("HttpRequest") ||
            className.contains("Timeout") ||
            "unable to resolve host" in text ||
            "failed to connect" in text ||
            "timeout" in text ->
            "Không có kết nối mạng. Kiểm tra Wi-Fi hoặc dữ liệu di động rồi thử lại."

        "invalid login credentials" in text || "invalid_credentials" in text ->
            "Email hoặc mật khẩu không đúng."

        "already registered" in text || "user_already_exists" in text ->
            "Email này đã được đăng ký. Hãy đăng nhập."

        "invalid_username" in text || "database error saving new user" in text ->
            "Tên người dùng không hợp lệ hoặc đã có người dùng."

        "weak_password" in text || ("password" in text && "at least" in text) ->
            "Mật khẩu quá yếu. Cần ít nhất 6 ký tự."

        "blocked" in text || "row-level security" in text ->
            "Không thực hiện được vì một trong hai người đã chặn người kia."

        "payload too large" in text || "exceeded the maximum allowed size" in text ->
            "File quá lớn (tối đa 5 MB)."

        "bucket not found" in text ->
            "Máy chủ chưa bật lưu ảnh và ghi âm. Hãy chạy file supabase_migration_03_media.sql."

        "group_full" in text ->
            "Nhóm đã đủ 50 thành viên."

        "only_leader" in text ->
            "Chỉ trưởng nhóm mới làm được việc này."

        "poll_closed" in text ->
            "Bình chọn này đã kết thúc."

        "invalid_poll" in text ->
            "Bình chọn cần một câu hỏi (tối đa 200 ký tự) và từ 2 đến 10 lựa chọn."

        "invalid_note" in text ->
            "Ghi chú cần từ 1 đến 2000 ký tự."

        "invalid_reminder_time" in text ->
            "Thời gian nhắc hẹn phải ở tương lai (tối đa 2 năm)."

        "invalid_reminder" in text ->
            "Nhắc hẹn cần nội dung từ 1 đến 200 ký tự."

        "invalid_bio" in text ->
            "Lời giới thiệu tối đa 300 ký tự."

        "invalid_birthday" in text ->
            "Ngày sinh không hợp lệ."

        "not_allowed" in text ->
            "Bạn không có quyền làm việc này."

        ("group_polls" in text || "group_notes" in text || "group_reminders" in text ||
            "create_group_" in text || "vote_group_poll" in text ||
            "update_my_profile_details" in text) &&
            ("does not exist" in text || "could not find" in text || "schema cache" in text) ->
            "Máy chủ chưa có phần này. Hãy chạy file supabase_migration_29_profile_group_board.sql."

        "invalid_group_name" in text ->
            "Tên nhóm cần từ 1 đến 60 ký tự."

        "rate limit" in text || "over_request_rate_limit" in text || "rate_limited" in text ->
            "Thao tác quá nhanh. Chờ một lát rồi thử lại."

        "email" in text && "invalid" in text ->
            "Địa chỉ email không hợp lệ."

        "signups not allowed" in text || "signup_disabled" in text ->
            "Máy chủ đang tắt chức năng đăng ký."

        else -> "Có lỗi xảy ra: ${message ?: className}"
    }
}
