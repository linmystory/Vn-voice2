# Hướng dẫn cài đặt & build ứng dụng "Đọc Văn Bản" (TTS)

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
5. Copy file `.apk` vào điện thoại Android, mở lên để cài (cần bật "Cho phép
   cài từ nguồn không xác định" trong Cài đặt nếu máy hỏi).

Workflow đã tự động thực hiện toàn bộ các bước sau, bạn **không cần làm gì
thêm**:
- Tạo project Android từ Capacitor (`npx cap add android`)
- Bật hỗ trợ biên dịch Kotlin
- Copy plugin `TtsFileSaverPlugin.kt` và `MainActivity.java` vào đúng vị trí
- Copy `file_paths.xml` và **vá `AndroidManifest.xml`** để thêm quyền lưu trữ
  (`WRITE_EXTERNAL_STORAGE`, chỉ dùng cho Android 9 trở xuống) và khai báo
  `FileProvider` — thiếu bước này tính năng "Lưu âm thanh" sẽ bị crash trên
  máy Android 9 trở xuống.
- Build file APK bản debug

## Cách 2 — Build thủ công trên máy tính (cần Android Studio / Android SDK)

Yêu cầu: Node.js 18+, JDK 17, Android SDK (thông qua Android Studio).

```bash
npm install
npx cap add android

# Bật hỗ trợ Kotlin cho project Android (chỉ cần làm 1 lần)
# — xem chi tiết 3 dòng sed trong .github/workflows/build.yml, bước
#   "Bật hỗ trợ biên dịch Kotlin cho project Android" —

# Copy plugin native vào project
mkdir -p android/app/src/main/java/com/docdoc/app
cp native-plugin/TtsFileSaverPlugin.kt android/app/src/main/java/com/docdoc/app/
cp native-plugin/MainActivity.java android/app/src/main/java/com/docdoc/app/

# Copy file cấu hình FileProvider và vá AndroidManifest.xml (BẮT BUỘC)
mkdir -p android/app/src/main/res/xml
cp native-plugin/res/xml/file_paths.xml android/app/src/main/res/xml/
python3 scripts/patch_manifest.py

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
- Trên Android 9 trở xuống, lần đầu bấm "Lưu âm thanh" ứng dụng sẽ hiện hộp
  thoại xin quyền **Bộ nhớ (Storage)** — cần bấm "Cho phép" để tính năng này
  hoạt động. Trên Android 10 trở lên không cần xin quyền này.
