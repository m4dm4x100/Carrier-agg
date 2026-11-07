# Carrier Control

Standalone Android app project for diagnosing LTE carrier aggregation on the OnePlus Nord CE 3 (CPH2569), Android 15 / OxygenOS.

The app requests only runtime permissions needed for telephony diagnostics. It has no internet permission, analytics, ads, root, Shizuku, ADB, or companion app.

**Important:** Android restricts modem/network-policy changes. The app checks carrier privileges before attempting the public TelephonyManager allowed-network-types API. If Jio does not grant carrier privileges to this app, the control remains unavailable; the app will not pretend the setting changed.

## Build

Open in Android Studio or run the GitHub Actions workflow **Build Android APK**. The debug APK is uploaded as the `CarrierControl-debug-apk` artifact.

Set Jio SIM 1 as the default mobile-data SIM before testing.
