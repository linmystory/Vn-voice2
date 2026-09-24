#!/usr/bin/env python3
"""
Bổ sung vào AndroidManifest.xml do `npx cap add android` tự sinh ra:
  1. Quyền android.permission.WRITE_EXTERNAL_STORAGE (chỉ tới API 28) — cần cho
     việc lưu file WAV vào bộ nhớ công khai trên Android 9 trở xuống.
  2. Khai báo <provider> FileProvider với authority "${applicationId}.fileprovider".

Thiếu bước này, tính năng "Lưu âm thanh (file WAV)" sẽ CRASH ngay lập tức trên
mọi máy Android 9 trở xuống (API <= 28), vì plugin TtsFileSaverPlugin.kt gọi
FileProvider.getUriForFile() nhưng project Capacitor mặc định KHÔNG tự khai báo
provider này (chỉ có sẵn khi cài thêm @capacitor/camera hoặc @capacitor/filesystem).

Chạy độc lập bằng: python3 scripts/patch_manifest.py
"""

import sys

MANIFEST_PATH = "android/app/src/main/AndroidManifest.xml"

PERMISSION_TAG = (
    '<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" '
    'android:maxSdkVersion="28" />\n'
)

PROVIDER_BLOCK = (
    '        <provider\n'
    '            android:name="androidx.core.content.FileProvider"\n'
    '            android:authorities="${applicationId}.fileprovider"\n'
    '            android:exported="false"\n'
    '            android:grantUriPermissions="true">\n'
    '            <meta-data\n'
    '                android:name="android.support.FILE_PROVIDER_PATHS"\n'
    '                android:resource="@xml/file_paths" />\n'
    '        </provider>\n'
)


def main():
    try:
        with open(MANIFEST_PATH, "r", encoding="utf-8") as f:
            content = f.read()
    except FileNotFoundError:
        sys.exit(f"Không tìm thấy {MANIFEST_PATH}. Hãy chạy 'npx cap add android' trước.")

    changed = False

    if "WRITE_EXTERNAL_STORAGE" not in content:
        idx = content.find("<application")
        if idx == -1:
            sys.exit("Không tìm thấy thẻ <application> trong AndroidManifest.xml")
        content = content[:idx] + PERMISSION_TAG + content[idx:]
        changed = True

    if "fileprovider" not in content:
        idx = content.rfind("</application>")
        if idx == -1:
            sys.exit("Không tìm thấy thẻ </application> trong AndroidManifest.xml")
        content = content[:idx] + PROVIDER_BLOCK + content[idx:]
        changed = True

    if changed:
        with open(MANIFEST_PATH, "w", encoding="utf-8") as f:
            f.write(content)
        print("Đã cập nhật AndroidManifest.xml: thêm quyền lưu trữ + FileProvider.")
    else:
        print("AndroidManifest.xml đã có sẵn quyền lưu trữ và FileProvider, bỏ qua.")


if __name__ == "__main__":
    main()
