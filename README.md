# Clipboard Bridge 📡🔗

A secure, automatic clipboard synchronization utility between **Windows** and **Android**. Synchronizes text and images seamlessly over local Wi-Fi with AES-GCM encryption.

---

## Features 🚀

- **🔏 Secure Connection:** Communications are encrypted end-to-end using AES-GCM (derived from a unique 16-character key).
- **📱 QR Code Pairing:** Scan a dynamically generated QR code from your phone screen to pair instantly.
- **⚙️ Background Sync & System Tray:** The Windows app runs quietly in your System Tray. Right-click to toggle pause, open settings, or set it to run on startup.
- **📡 Automatic Connection:** Discovers and pairs devices automatically over local Wi-Fi. A manual IP fallback is available if UDP broadcasts are restricted.
- **⚡ Wi-Fi Only Mode:** The Android app automatically restricts data synchronization to Wi-Fi connections, conserving mobile data.
- **🔔 Native Toasts:** Triggers native OS Toast notifications on Windows and Toast bubbles on Android upon successful sync.

---

## Getting Started ⚙️

### 🖥️ Windows Server & GUI

#### Prerequisites
- Python 3.10+
- Install dependencies:
  ```bash
  pip install pillow qrcode win11toast pystray pywin32
  ```

#### Running the Server
1. Navigate to the `windows/` directory and run:
   ```bash
   python gui.py
   ```
2. Right-click the system tray icon and select **Show Settings / QR Code** to view your pairing QR code.

---

### 📱 Android Application

#### Prerequisites
- Android device running Android 8.0 (API 26) or higher.

#### Running the App
1. Build and install the APK via Android Studio or run from the `android/` folder:
   ```bash
   ./gradlew installDebug
   ```
2. Tap **Scan QR to Pair** and scan the QR code displayed on your Windows settings panel.
3. The service will automatically start in the background and establish a connection!
