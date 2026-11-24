# Reclaim 1.0 pilot · 24 November 2026

**Built.** Reclaim-1.0.apk is the signed Android installer. Reclaim-1.0-Source.zip contains the matching Android Studio project, its companion Node service and the Meta integration guide. The application ID is `uk.co.agentengines.followup`, version code 1. Minimum API 26, target API 35. The APK contains ARM and x86 native libraries in both 32- and 64-bit versions.

**Process.** AgentEngines team agreement 1.0 and the native Android delivery guide, using the neutral Kotlin and Jetpack Compose project, its pinned Gradle wrapper and direct APK delivery. The server is an isolated single-business pilot.

**Verified.** The exact APK passed signature and package inspection and installed on an Android 9 emulator. Five on-device tests passed. On a separate Android 13 emulator, the exact installer also passed the notification-permission check with permission initially denied and subsequently granted, including duplicate-alert suppression. They covered YES, NO, refilling, silence, a new Messenger booking, local connection-key protection, pairing to a simulated HTTPS service and a private open-slot alert. The launcher flow also passed with Wi-Fi and mobile data disabled. A sample cancellation still appeared as an open slot after a forced app stop and relaunch. Sixteen service tests passed on Node 24.22.3. They covered the 48- and 24-hour schedule, expiry and late creation, the UK clock change, replies with or without a visible WhatsApp number, the Messenger link code and Page identity, opt-outs, ambiguous replies, repeated events, competing workers, interrupted delivery, signed webhooks, credential checks and a service restart with a persistent database. The service tests used simulated Meta transport. A clean build from an exported copy of the source succeeded. All 149 unpacked APK entries matched the handoff APK by SHA-256.

**Still open.** Complete a physical-device installation check. The service operator needs to configure the actual Meta test assets, eligible templates, webhook subscriptions and server settings, then confirm delivery and replies on both real channels. The server needs a running HTTPS test host and storage that survives deployment. This APK uses a development signer for the pilot. Owner-controlled release signing is a separate step before business distribution.

| APK identity | SHA-256 |
| --- | --- |
| Installer | `5a7d99f9b01e4bc41886331b69bf9dbb7f3c3b68dda089e55883566e4b5b81e4` |
| Signing certificate | `8e293f028ef3bcdf13fb2db7dd71dcb37667016e5ed97517b9458a284d582666` |
