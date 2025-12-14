# WifiP2P

# Offline Device-to-Device Messaging with Wi-Fi Direct (Android)

A simple Android app that lets phones talk directly to each other —  
**no internet, no Bluetooth, and no Wi-Fi router required.**

The devices create their own temporary local network and exchange messages completely offline.

---

## 🚫 What this app does NOT need

- ❌ Internet connection
- ❌ Mobile data
- ❌ Bluetooth
- ❌ Wi-Fi network

The phones do **not** connect to a router.  
They connect **directly to each other**.

---

## ✅ What it uses instead

- ✅ **Wi-Fi Direct (Wi-Fi P2P)**

Wi-Fi Direct allows devices to communicate peer-to-peer using their Wi-Fi hardware, without accessing the internet or joining an existing network.

If both devices are in airplane mode (with Wi-Fi enabled), the app still works.

---

## 🧩 How it works (simple explanation)

- One device becomes **Parent (Group Owner)**

  - Creates a temporary local network
  - Acts as the group leader
  - **Sends messages** (Broadcaster)

- Other devices become **Child (Client)**
  - Find the Parent automatically
  - Connect directly
  - **Receive messages** in real time

All communication stays **inside the local connection** between devices.

---

## 🔁 Built for real-world reliability

Wi-Fi Direct can be unpredictable. This app handles that by:

- Retrying safely when discovery fails
- Remembering the last connected device
- Reconnecting automatically when possible
- Showing logs on screen so users know what’s happening

---

## ▶️ How to run

1. Open the project in Android Studio.
2. Install on **two or more physical Android devices**.
3. Enable Wi-Fi on both devices (internet is not required).
4. Launch the app and **grant all requested permissions** (Location & Nearby Devices).
   > **Important:** Android requires **Location** permission to discover nearby Wi-Fi Direct peers. If you deny this, the app will not find other devices.
5. Choose **Parent** on one device.
6. Choose **Child** on the other.

> ⚠️ **Emulator Warning:** Wi-Fi Direct is unreliable or unsupported on most Android emulators. Please use real physical devices for testing.

---

## 📄 License

MIT
