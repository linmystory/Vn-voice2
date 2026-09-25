# Hướng dẫn cài đặt & build ứng dụng "Đọc Văn Bản" (TTS)

> **Lưu ý phạm vi:** bản này CHỈ HỖ TRỢ ANDROID 10 (API 29) TRỞ LÊN.
> Toàn bộ code xử lý quyền lưu trữ runtime kiểu cũ (`WRITE_EXTERNAL_STORAGE`)
> và khai báo `FileProvider` cho Android 9 trở xuống đã được **loại bỏ** để
> gọn nhẹ hơn. Tính năng "Lưu âm thanh (file WAV)" dùng MediaStore/scoped
> storage của Android 10+, không cần xin quyền lúc chạy.

Ứng dụng chạy trên nền [Capacitor](https://capacitorjs.com/) (WebView + plugin
native Kotlin). Có 2 cách để có file APK: **build tự động qua GitHub Actions**
(khuyên dùng, không cần cài Android Studio) hoặc **build thủ công trên máy**.

---

## Cách 1 — Build tự động bằng GitHub Actions (khuyên dùng)

1. Tạo một repository GitHub mới (public hoặc private đều được), rồi đẩy toàn
   bộ thư mục này lên:
   ```bash
   git init
   git add .
   git commit -m "Init TTS app"
   git branch -M main
   git remote add origin <URL_repo_cua_ban>
   git push -u origin main
   ```
2. Vào tab **Actions** trên GitHub, workflow "Build Android APK" sẽ tự chạy
   ngay sau khi push (hoặc bấm **Run workflow** để chạy thủ công).
3. Chờ vài phút cho tới khi job "build" chạy xong (dấu tích xanh).
4. Mở job đó ra, kéo xuống mục **Artifacts**, tải file `app-debug-apk` về máy
   (đây là file `.apk` nén trong `.zip`, giải nén ra là cài được).
5. Copy file `.apk` vào điện thoại Android 10 trở lên, mở lên để cài (cần bật
   "Cho phép cài từ nguồn không xác định" trong Cài đặt nếu máy hỏi).

Workflow đã tự động thực hiện toàn bộ các bước sau, bạn **không cần làm gì
thêm**:
- Tạo project Android từ Capacitor (`npx cap add android`)
- Đặt `minSdkVersion = 29` trong `android/variables.gradle` để giới hạn
  ứng dụng chỉ cài được trên Android 10 trở lên
- Bật hỗ trợ biên dịch Kotlin
- Copy plugin `TtsFileSaverPlugin.kt` và `MainActivity.java` vào đúng vị trí
- Build file APK bản debug

## Cách 2 — Build thủ công trên máy tính (cần Android Studio / Android SDK)

Yêu cầu: Node.js 18+, JDK 17, Android SDK (thông qua Android Studio).

```bash
npm install
npx cap add android

# Giới hạn ứng dụng chỉ hỗ trợ Android 10 (API 29) trở lên — sửa dòng
# minSdkVersion trong android/variables.gradle thành 29 (bằng tay hoặc chạy):
sed -i 's/minSdkVersion = [0-9]\+/minSdkVersion = 29/' android/variables.gradle

# Bật hỗ trợ Kotlin cho project Android (chỉ cần làm 1 lần)
# — xem chi tiết 3 dòng sed trong .github/workflows/build.yml, bước
#   "Bật hỗ trợ biên dịch Kotlin cho project Android" —

# Copy plugin native vào project
mkdir -p android/app/src/main/java/com/docdoc/app
cp native-plugin/TtsFileSaverPlugin.kt android/app/src/main/java/com/docdoc/app/
cp native-plugin/MainActivity.java android/app/src/main/java/com/docdoc/app/

npx cap sync android
cd android
./gradlew assembleDebug
```

File APK sẽ nằm ở `android/app/build/outputs/apk/debug/app-debug.apk`.

---

## Ghi chú quan trọng

- Ứng dụng hoạt động **hoàn toàn ngoại tuyến**, dùng giọng đọc có sẵn trên máy
  (Google TTS / Samsung TTS / v.v. tùy máy). Nếu máy chưa cài giọng tiếng
  Việt, vào **Cài đặt > Ngôn ngữ & nhập > Chuyển văn bản thành giọng nói** để
  tải thêm giọng.
- Tính năng **"Lưu âm thanh (file WAV)"** chỉ hoạt động trong bản APK đã cài
  đặt, **không hoạt động** khi mở `www/index.html` trực tiếp bằng trình duyệt
  máy tính/điện thoại — vì nó cần plugin native Kotlin chỉ tồn tại bên trong
  ứng dụng đã đóng gói.
- Ứng dụng yêu cầu **Android 10 (API 29) trở lên**. Vì `minSdkVersion` đã đặt
  là 29, máy chạy Android 9 trở xuống sẽ **không cài được** ứng dụng (thay vì
  cài được rồi lỗi lúc dùng), tránh gây nhầm lẫn cho người dùng.
