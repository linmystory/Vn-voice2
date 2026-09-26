# Hướng dẫn cài đặt & build ứng dụng "Đọc Văn Bản" (TTS)

> **Cập nhật v2.2 — nâng cấp lên Capacitor 8 (công nghệ mới nhất hiện tại):**
> - Nâng `@capacitor/core`, `@capacitor/android`, `@capacitor/cli` từ `6.1.2` (đã cũ 2 major version) lên **`8.5.2`/`8.5.1`** — bản ổn định mới nhất tại thời điểm cập nhật.
> - **Lý do bắt buộc:** từ 31/8/2026, Google Play yêu cầu mọi app mới/bản cập nhật phải target Android 16 (API 36). Capacitor 6 mặc định chỉ target API 34 → sẽ bị Google Play từ chối. Capacitor 8 mặc định target đúng API 36, không cần vá thêm.
> - Nâng Kotlin `1.9.24` → `2.2.20`, JDK `17` → `21`, Node.js `20` → `22` trong CI, đúng theo yêu cầu chính thức của Capacitor 8.
> - **Lưu ý rủi ro đã biết (chưa build thử được do sandbox không có mạng):** Capacitor 8.5.x mặc định dùng AGP 8.13.0, hiện có 1 issue đang mở trên GitHub của Capacitor về việc Android Studio cảnh báo AGP 8.13.0 chưa được hỗ trợ chính thức (ionic-team/capacitor#8292) — cảnh báo này chủ yếu ảnh hưởng khi mở project bằng Android Studio GUI, không chắc có chặn build dòng lệnh `./gradlew assembleDebug` trong CI hay không. Một issue khác (#8355) về lỗi ProGuard chỉ ảnh hưởng project có dùng `@capacitor/haptics`/`@capacitor/keyboard` — project này không dùng 2 plugin đó nên không bị ảnh hưởng. Nếu CI build lỗi sau khi cập nhật, khả năng cao là do issue #8292 — cân nhắc pin tạm AGP về `8.12.2` trong `android/build.gradle` như một phương án dự phòng.

> **Cập nhật v2.1:**
> - Bổ sung `getEnginesWithVoices()` trong plugin native: lấy **toàn bộ bộ đọc (TTS engine)** cài trên máy (Google TTS, Samsung TTS, v.v.) kèm **toàn bộ giọng đọc trong từng bộ đọc** — trước đây chỉ lấy giọng của engine mặc định. Dropdown "Bộ đọc" ở chế độ native giờ liệt kê đầy đủ như chế độ Web Speech API.
> - Bổ sung `setEngine()`: khi người dùng đổi bộ đọc trong dropdown, app chuyển đúng engine Android thật sự dùng để đọc/lưu file, không chỉ đổi giao diện.

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

---

## Khắc phục sự cố (Troubleshooting)

**App mở lên nhưng báo "Không có bộ đọc khả dụng" / "Không tìm thấy giọng đọc":**
Máy chưa cài engine Text-to-Speech nào (thường gặp trên máy Trung Quốc,
ROM tuỳ biến đã gỡ Google TTS). Vào **Cài đặt > Ngôn ngữ & nhập liệu >
Chuyển văn bản thành giọng nói (Text-to-speech)**, chọn một engine (Google
TTS hoặc Samsung TTS) và tải gói giọng tiếng Việt. Đây không phải lỗi của
ứng dụng — plugin đã được vá để phát hiện và báo đúng tình huống này thay vì
treo vô thời hạn.

**Build GitHub Actions báo lỗi ở bước `sed` (không tìm thấy `minSdkVersion`
hoặc `dependencies {`):**
Điều này xảy ra nếu một phiên bản Capacitor mới hơn đổi cấu trúc file
`android/variables.gradle` hoặc `android/build.gradle`. Cách xử lý:
1. Mở log của bước bị lỗi trong tab Actions để xem `sed` không khớp được
   dòng nào.
2. Mở file tương ứng (`android/variables.gradle` hoặc `android/build.gradle`)
   sau bước `npx cap add android`, sửa tay dòng `minSdkVersion` thành `29`
   hoặc thêm thủ công dòng `classpath` Kotlin vào đúng khối `dependencies {}`
   trong `buildscript`.
3. Có thể ghim lại phiên bản Capacitor cũ hơn (đã test hoạt động tốt) trong
   `package.json` nếu không muốn sửa lại script `sed`.

**Build lỗi vì xung đột phiên bản Kotlin/AGP:**
Thử nâng số phiên bản `kotlin-gradle-plugin` và `kotlin-stdlib` trong
`.github/workflows/build.yml` lên bản mới hơn tương thích với phiên bản
Android Gradle Plugin (AGP) mà `npx cap add android` tạo ra, hoặc hạ phiên
bản `@capacitor/android` trong `package.json` xuống bản đã biết chạy ổn.

**Bấm "Lưu âm thanh" báo "hết bộ nhớ" với văn bản rất dài:**
Giảm bớt độ dài văn bản (ứng dụng đã giới hạn 25.000 ký tự ở khung nhập),
hoặc chia văn bản thành nhiều lần lưu file nhỏ hơn. Bản vá đã bắt riêng lỗi
`OutOfMemoryError` để báo rõ nguyên nhân thay vì làm ứng dụng đóng đột ngột.

**App đọc giọng bị sai ngôn ngữ / đọc bằng giọng tiếng Anh dù đã nhập tiếng
Việt:** Máy chưa cài gói giọng tiếng Việt cho engine TTS đang dùng. Plugin
đã được vá để tự động rơi về tiếng Anh (thay vì lỗi hẳn) khi ngôn ngữ yêu
cầu không có sẵn — hãy cài thêm giọng tiếng Việt theo hướng dẫn ở mục
"Không có bộ đọc khả dụng" phía trên để giọng đọc đúng như mong muốn.
