# Reclaim by AgentEngines

A native Android appointment confirmation app for the business owner. Customers respond in WhatsApp or Facebook Messenger. They use their existing chat app.

## Try it on your phone

Open **Reclaim-1.0.apk**, tap **Install**, then **Open**. On a fresh installation tap **Try a sample booking → Reply NO → Mark slot refilled**. A YES keeps the appointment. The demo works offline and stores its fictional appointments on the phone. You can also create a booking, preview both reminders, and try the no-reply case.

## Connect a test channel

Use **META-SETUP.md** to configure the messaging channels. The companion service is under **service/**. A test connection needs a reachable HTTPS service, a Reclaim device key, the configured channel and an approved matching template. The app has no preconfigured destination. Demo data stays on this device. Connected appointments live on the single-business server. Disconnecting removes the device key and leaves the server’s schedule running.

## Rebuild in Android Studio

Open this project with JDK 17, Android SDK 35 and Build Tools 34.0.0. The included Gradle 8.9 wrapper pins the official distribution checksum. Minimum Android version is 8.0 (API 26). Use the following commands on Unix.

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
./gradlew :app:connectedDebugAndroidTest
```

Use `gradlew.bat` on Windows. The first command builds the app and on-device tests. The second requires a running emulator or test phone. Run the companion service checks with `cd service && node --test` on Node 22.13 or above.

The supplied APK is a development-signed pilot. Preserve the signing identity and increase the version code for an in-place update. A rebuild in a new environment normally uses a different development key. Arrange owner-controlled release signing before distributing to businesses.

The app uses Kotlin, Jetpack Compose and WorkManager. It opens no contacts, SMS or call log. Android notification permission is requested only when the owner chooses to enable open-slot alerts. Background scheduling and network availability determine how soon those alerts appear.

**RELEASE.md** records the exact build, test environment and remaining live checks.
