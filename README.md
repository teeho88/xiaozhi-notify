# XiaoZhi Notify (app Android)

App chuyển thông báo điện thoại lên đồng hồ XiaoZhi, **tự tìm địa chỉ đồng hồ qua mDNS** nên đổi mạng / đổi IP không phải cấu hình lại. Thay thế hoàn toàn MacroDroid.

## Cách hoạt động

- Firmware quảng bá mDNS: hostname `xiaozhi.local`, service `_xiaozhi-notify._tcp` cổng 80.
- App dùng `NsdManager` (multicast trong LAN, **không phụ thuộc router phân giải `.local`**) để tìm IP hiện tại của đồng hồ, cache lại.
- Mỗi thông báo → POST `http://<ip>/api/notify` (JSON `{token, id, app, title, text}`).
- Gửi lỗi (đổi mạng → IP cũ sai) → app tự dò lại rồi gửi lại.

## Build APK

Cần Android Studio (Giraffe trở lên) hoặc Gradle 8.9 + JDK 17.

1. Mở thư mục `android-notify-app/` bằng Android Studio → để nó tự sync (tạo gradle wrapper, tải dependency).
2. `Build > Build APK(s)` → APK ở `app/build/outputs/apk/debug/app-debug.apk`.

Hoặc dòng lệnh (sau khi có gradle wrapper):
```
cd android-notify-app
gradle wrapper            # chỉ cần lần đầu, nếu chưa có gradlew
./gradlew assembleDebug
```

### Build qua GitHub Actions (không cài gì trên máy)
Push `android-notify-app/` làm gốc repo → tab **Actions** tự build **APK release đã ký** → tải artifact `xiaozhi-notify-apk` (`app-release.apk`). Workflow tự sinh keystore và ký.

> Mỗi lần build CI tạo key mới → chữ ký khác nhau, nên muốn **cập nhật đè** (không gỡ app cũ) thì giữ lại đúng file APK đó, hoặc lưu keystore thành secret rồi giải mã trong workflow thay vì sinh mới.

## Dùng trên điện thoại

1. Cài APK, mở app.
2. Bấm **Cấp quyền đọc thông báo** → bật XiaoZhi Notify trong danh sách.
3. Mở trang `/notify` trên đồng hồ (qua web config port 80), copy **Token**, dán vào app, **Lưu token**.
4. Bấm **Tìm đồng hồ trong WiFi** → **Gửi thử** để kiểm tra.

Sau bước này: đổi WiFi, đổi mạng, đổi IP đều không cần đụng lại — app tự dò.

> Điện thoại và đồng hồ phải **cùng mạng LAN** (mDNS chỉ chạy trong LAN). Để chạy khác mạng/4G cần phương án cloud relay (chưa làm trong app này).
