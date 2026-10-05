# MayChat

Ứng dụng nhắn tin và gọi điện cho Android, viết bằng Kotlin và Jetpack Compose. Mã nguồn nằm trên GitHub, APK được build tự động bằng GitHub Actions.

**Trạng thái hiện tại: Giai đoạn 1** (khung dự án, màn hình chính đơn giản, build APK tự động). Chưa có đăng nhập, chưa có nhắn tin.

## Tính năng theo giai đoạn

| Giai đoạn | Nội dung | Trạng thái |
|---|---|---|
| 1 | Dự án Android, Compose, GitHub Actions build APK | Đang kiểm tra |
| 2 | Đăng ký, đăng nhập, đăng xuất (Firebase Auth) | Chưa làm |
| 3 | Hồ sơ người dùng, username, avatar | Chưa làm |
| 4 | Tìm người dùng, kết bạn | Chưa làm |
| 5 | Nhắn tin realtime giữa 2 điện thoại thật | Chưa làm |
| 6 | Thông báo đẩy | Chưa làm |
| 7 | Gọi thoại WebRTC | Chưa làm |
| 8 | Gọi video WebRTC | Chưa làm |
| 9 | Gửi ảnh | Chưa làm |
| 10 | Tin nhắn thoại | Chưa làm |
| 11 | Tăng cường bảo mật | Chưa làm |
| 12 | Hoàn thiện giao diện, tối ưu | Chưa làm |

## Công nghệ

| Thành phần | Phiên bản | Ghi chú |
|---|---|---|
| Android Gradle Plugin | 9.2.0 | Tự biên dịch Kotlin (built-in Kotlin) |
| Gradle | 9.4.1 | Mức tối thiểu mà AGP 9.2.0 yêu cầu |
| Kotlin | 2.3.10 | |
| JDK | 17 | |
| Compose BOM | 2025.12.00 | Chọn bản đã ổn định lâu, sẽ nâng sau khi build đầu tiên thành công |
| compileSdk / targetSdk | 36 | |
| minSdk | 26 | Android 8.0 trở lên |

Tên gói (package name): `com.maychat.app`. Firebase ở Giai đoạn 2 sẽ gắn với tên này, nên nếu muốn đổi thì đổi **trước** Giai đoạn 2.

## Cấu trúc thư mục

```
MayChat/
├── .github/workflows/build-apk.yml   ← GitHub Actions build APK
├── app/
│   ├── build.gradle.kts              ← cấu hình module app, danh sách thư viện
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/maychat/app/
│       │   ├── MainActivity.kt       ← điểm khởi động app
│       │   └── ui/
│       │       ├── home/HomeScreen.kt
│       │       └── theme/Theme.kt
│       └── res/                      ← chữ, màu, icon
├── build.gradle.kts                  ← phiên bản các plugin
├── settings.gradle.kts
├── gradle.properties
├── .gitignore
└── README.md
```

Dự án **không có** `gradlew` và thư mục `gradle/wrapper`. Lý do: file `gradle-wrapper.jar` là file nhị phân, khó tải lên đúng qua giao diện web. GitHub Actions tự cài Gradle 9.4.1 nên không cần. Xem mục "Build trên máy tính" nếu bạn muốn tạo wrapper.

## Đưa dự án lên GitHub (lần đầu, dùng giao diện web)

**BƯỚC 1. Tạo tài khoản.** Vào https://github.com, bấm **Sign up**, làm theo hướng dẫn, xác nhận email.

**BƯỚC 2. Tạo repository.** Bấm dấu **+** ở góc trên bên phải → **New repository**.
- Repository name: `MayChat`
- Chọn **Public** (GitHub Actions miễn phí không giới hạn phút cho repo public) hoặc **Private** (có hạn mức phút miễn phí mỗi tháng).
- **Không** tick "Add a README file", **không** chọn .gitignore, **không** chọn license (dự án đã có sẵn).
- Bấm **Create repository**.

**BƯỚC 3. Tải file lên.** Giải nén `MayChat.zip` trên máy tính. Ở trang repo vừa tạo, bấm dòng chữ **uploading an existing file**. Mở thư mục `MayChat` đã giải nén, chọn **tất cả những gì bên trong** (các thư mục `.github`, `app` và các file) rồi kéo thả vào trang web. Chờ tải xong, kéo xuống dưới, bấm **Commit changes**.

Lưu ý: kéo **nội dung bên trong** thư mục `MayChat`, không kéo chính thư mục `MayChat`. File `build.gradle.kts` phải nằm ngay ở trang đầu của repo.

**BƯỚC 4. Kiểm tra thư mục `.github`.** Ở trang đầu của repo phải thấy thư mục `.github`. Nếu không thấy (một số trình duyệt bỏ qua thư mục bắt đầu bằng dấu chấm), tạo tay:
- Bấm **Add file → Create new file**.
- Ở ô tên file gõ đúng: `.github/workflows/build-apk.yml`
- Mở file `build-apk.yml` trong thư mục đã giải nén bằng Notepad, copy toàn bộ, dán vào.
- Bấm **Commit changes**.

**BƯỚC 5. Bạn sẽ thấy gì.** Ngay sau khi commit, tab **Actions** xuất hiện một lượt chạy tên "Build APK" với chấm vàng (đang chạy). Lần đầu mất khoảng 3 đến 8 phút.

## Tải APK từ GitHub Actions

1. Mở repo → tab **Actions**.
2. Cột trái chọn **Build APK**.
3. Bấm vào lượt chạy trên cùng (phải có dấu tích xanh).
4. Kéo xuống cuối trang, mục **Artifacts**.
5. Bấm **MayChat-debug-apk** để tải. File tải về là `.zip`, giải nén ra được `app-debug.apk`.

Muốn chạy lại thủ công: tab **Actions** → **Build APK** → **Run workflow** → **Run workflow**.

Phải đăng nhập GitHub mới tải được artifact. Artifact tự xóa sau 14 ngày, chạy lại workflow để có bản mới.

## Cài APK lên điện thoại

1. Chép `app-debug.apk` sang điện thoại (Zalo, Google Drive, cáp USB đều được).
2. Mở file trên điện thoại. Android sẽ hỏi quyền "Cài đặt ứng dụng không rõ nguồn gốc" cho ứng dụng bạn dùng để mở file: bật lên.
3. Bấm **Cài đặt**. Nếu Google Play Protect cảnh báo, chọn **Vẫn cài đặt**.

**Quan trọng:** hiện tại mỗi lần GitHub Actions build sẽ ký APK bằng một khóa debug mới. Vì vậy khi cài bản mới đè lên bản cũ, Android sẽ báo "Ứng dụng chưa được cài đặt". Cách xử lý: gỡ bản cũ rồi cài bản mới. Việc này sẽ được sửa ở giai đoạn sau bằng một khóa ký cố định lưu trong GitHub Secrets.

## Build trên máy tính (không bắt buộc)

Cách dễ nhất: cài Android Studio, chọn **Open**, trỏ tới thư mục dự án, chờ đồng bộ, bấm **Run**.

Bằng dòng lệnh, cần cài JDK 17, Android SDK và Gradle 9.4.1, sau đó chạy một lần để tạo wrapper:

```
gradle wrapper --gradle-version 9.4.1
```

Từ đó build bằng:

```
./gradlew assembleDebug        (macOS / Linux)
gradlew.bat assembleDebug      (Windows)
```

APK nằm ở `app/build/outputs/apk/debug/app-debug.apk`.

## Bảo mật

Không bao giờ đưa lên GitHub: file khóa service account của Firebase, GitHub token, file keystore (`.jks`, `.keystore`), mật khẩu. File `.gitignore` đã chặn sẵn các loại file này, kể cả `google-services.json`. Khi cần, chúng sẽ được đưa vào build thông qua **GitHub Secrets** (hướng dẫn ở Giai đoạn 2).

## Xử lý lỗi thường gặp

| Hiện tượng | Nguyên nhân có thể | Cách xử lý |
|---|---|---|
| Tab Actions không có lượt chạy nào | Thiếu file `.github/workflows/build-apk.yml`, hoặc nhánh chính không tên `main` | Làm lại BƯỚC 4. Kiểm tra tên nhánh ở góc trên bên trái danh sách file |
| Lỗi `Directory does not contain a Gradle build` | Tải nhầm cả thư mục `MayChat` nên file nằm sâu một cấp | `build.gradle.kts` phải nằm ở trang đầu repo. Xóa repo, tạo lại và tải đúng nội dung bên trong |
| Lỗi `Could not find ...` hoặc `Could not resolve ...` | Một phiên bản thư viện/plugin không tồn tại | Copy dòng lỗi gửi cho người hỗ trợ, chỉ cần sửa số phiên bản trong `build.gradle.kts` hoặc `app/build.gradle.kts` |
| Lỗi `Minimum supported Gradle version is ...` | Phiên bản AGP cần Gradle mới hơn | Sửa `gradle-version` trong `build-apk.yml` |
| "Ứng dụng chưa được cài đặt" | Bản cũ ký bằng khóa khác | Gỡ bản cũ rồi cài lại |
| "Có vấn đề khi phân tích gói" | Điện thoại dưới Android 8.0, hoặc đang mở file `.zip` chứ không phải `.apk` | Giải nén trước, kiểm tra phiên bản Android |

Khi lượt chạy bị dấu X đỏ: bấm vào lượt chạy → bấm job **Build debug APK** → mở bước có dấu X → copy khoảng 30 dòng cuối có chữ `error` hoặc `FAILURE`.
