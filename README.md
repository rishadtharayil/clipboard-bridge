# Clipboard Bridge 📡🔗

A premium, secure, and automatic clipboard synchronization utility between **Windows** and **Android**. Synchronizes text and images seamlessly over local Wi-Fi with AES-GCM encryption.

---

## Features 🚀

- **🔏 Secure 16-Character Key Pairing:** Generates a unique 16-character connection key. Communications are encrypted end-to-end using AES-GCM (SHA-256 derived keys).
- **📱 QR Code Pairing:** Scan a dynamically generated QR code from your phone screen to pair instantly.
- **⚙️ Background Sync & System Tray:** The Windows app lives in your System Tray. Right-click to toggle pause, open settings, or set it to automatically run on startup.
- **📡 Multi-Interface Auto-Discovery:** Dynamic socket broadcasting guarantees pairing even when complex virtual adapters (like WSL, Hyper-V, VMware) are installed.
- **🔌 Direct IP Fallback:** Manually connect by typing in the PC's IP address if UDP broadcasts are restricted on your router.
- **⚡ Wi-Fi Only Mode:** The Android app automatically restricts data synchronization to Wi-Fi connections, conserving mobile data.
- **🔔 Native Toasts:** Triggers native OS Toast notifications on Windows and Toast bubbles on Android upon successful sync.

---

## Getting Started ⚙️

### 🖥️ Windows Server & GUI

#### Prerequisites
- Python 3.10+ installed and on `PATH`.
- Install python dependencies:
  ```bash
  pip install pillow qrcode win11toast pystray pywin32
  ```

#### Running the Server
1. Navigate to the `windows/` directory:
   ```bash
   cd windows
   ```
2. Launch the application:
   ```bash
   python gui.py
   ```
3. A "CB" icon will appear in your system tray. Right-click it and select **Show Settings / QR Code** to view your pairing QR code.

---

### 📱 Android Application

#### Prerequisites
- Android device running Android 8.0 (API 26) or higher.
- Built using Android Studio / Gradle.

#### Running the App
1. Build and install the APK via Android Studio or run from the `android/` folder:
   ```bash
   ./gradlew installDebug
   ```
2. Launch **Clipboard Bridge** on your device.
3. Tap **Scan QR to Pair** and scan the QR code displayed on your Windows settings panel.
4. The service will automatically start in the foreground and establish a connection!
