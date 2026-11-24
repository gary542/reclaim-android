# Reclaim 1.0 · Meta test connection

The Android app includes an offline Demo workspace. The companion service in `service/` connects a business to WhatsApp and Facebook Messenger. Connecting a service starts with an empty, separate appointment book.

## What the service operator supplies

- The HTTPS origin of the isolated test service, for example `https://YOUR-TEST-HOST`.
- A Reclaim device access key delivered privately to the app owner. The phone stores it with Android Keystore protection. Meta access tokens, the app secret and webhook verification token belong in the hosting environment.
- A short result for each channel. Record the test recipient, with the identifier masked, whether a real reminder arrived, whether YES or NO updated the correct booking, and whether the Page/number and template are approved. Report configuration status separately from end-to-end delivery.

## 1. Prepare the two Meta channels

**WhatsApp.** In Meta’s developer dashboard, add the WhatsApp use case and use its test business number. Add a consenting tester’s handset as a verified recipient. Record the test number’s **phone number ID** and the WhatsApp Business Account ID. Use API Setup to verify the Meta sample first. Create a utility appointment template with the exact shape below. If the test account only offers the built-in sample, the sample proves connectivity. The appointment test remains open until a matching template is approved for that sender. [Meta’s getting-started guide](https://developers.facebook.com/documentation/business-messaging/whatsapp/get-started) · [Meta test resources](https://developers.facebook.com/documentation/business-messaging/whatsapp/about-the-platform)

**Messenger.** Connect the business’s Facebook Page, enable messaging, and register the test roles required for the app’s mode. Confirm the Page can send a utility message for this appointment use case, with the relevant Page messaging and utility permissions and an approved Page-owned template. The service uses a Page-scoped customer ID from an incoming conversation. The customer links a booking by sending the private one-use code on its detail screen to the Page. Use the authorised tester’s PSID for the test allowlist. If the Page lacks utility access, leave the Messenger sender settings empty and test WhatsApp independently. [Messenger overview](https://developers.facebook.com/documentation/business-messaging/messenger-platform/overview) · [Utility templates, permissions and send flow](https://developers.facebook.com/documentation/business-messaging/messenger-platform/send-messages/utility-messages/)

**Use one text-only utility template per channel.** Select positional parameters in this order.

| Position | Reclaim supplies | Example for template approval |
| --- | --- | --- |
| 1 | Customer name | Jamie |
| 2 | Business display name | Example Studio |
| 3 | Appointment date, time and zone | Thu 26 Nov 2026, 14:00 GMT |

Suggested template for approval.

“Hi {{1}}, this is {{2}} checking your appointment on {{3}}. Can you still make it? Reply YES to confirm or NO to release your slot.” Use the approved name and exact language code in each channel’s settings. The same approved template covers the initial reminder and one follow-up. Plain one-word replies work in the customer’s normal chat.

## 2. Run the test service

The service is a single-business pilot. Use an isolated Node service with **Node 22.13+ or Node 24**, public HTTPS, one always-running worker, and a persistent, writable directory outside deployment files. The built-in SQLite database and its WAL files must survive a process restart and a redeploy. Confirm these properties with the hosting provider. A sleeping service delays reminders.

Use `service/` as the application root. Startup is `npm start` or `node server.mjs`. There are zero external server packages. For local checks use `node --test`. The reverse proxy must forward raw webhook bodies and the signing header intact. Point the health check to `GET /health`.

| Server environment setting | Value to supply |
| --- | --- |
| `BUSINESS_NAME` | The name customers recognise. It fills the second template parameter. |
| `DATABASE_PATH` | Absolute filename in a private, persistent, writable directory, e.g. `/persistent/reclaim/pilot.sqlite`. Create the directory first. |
| `PORT`, `BIND_ADDRESS` | Port assigned by the host. The default bind address is loopback. Set it to the private or container interface expected by the host’s HTTPS proxy. |
| `DEVICE_TOKEN_SHA256` | Run `node issue-device-key.mjs` in a private terminal. Put its SHA-256 value on the server and provide the separately printed device key to the app owner. Replacing the hash revokes that key. |
| `MESSAGING_MODE` | Starts at `off`. Use `test` with the tester allowlists. `live` is a later, explicit operator decision. |
| `GRAPH_VERSION` | A Graph API version supported by both channels and confirmed in the dashboard, including the leading v. |
| `META_APP_SECRET`, `WEBHOOK_VERIFY_TOKEN` | Meta app secret and a separate unpredictable value chosen by the operator for subscription verification. Both channels use the same app in this pilot. |
| `WA_SENDER_ID`, `WA_ACCESS_TOKEN` | WhatsApp phone number ID and server-side token authorised for that number. Scope and lifetime must cover the test. |
| `WA_TEMPLATE`, `WA_LANGUAGE` | Approved WhatsApp template name and locale. |
| `WA_TEST_RECIPIENTS` | Comma-separated verified test numbers in full international form, including the plus. |
| `PAGE_SENDER_ID`, `PAGE_ACCESS_TOKEN` | Facebook Page ID and Page token authorised for the test. |
| `PAGE_TEMPLATE`, `PAGE_LANGUAGE` | Approved Messenger utility template name and locale. |
| `PAGE_TEST_RECIPIENTS` | Comma-separated numeric PSIDs for authorised Page testers, obtained through the Page’s test integration. |

Register the same HTTPS callback on both products at `/webhooks/meta`. Meta’s GET challenge uses the verification token. POST deliveries require the Meta app signature over the raw body. Subscribe the test WABA to its `messages` field and app, and the Page to `messages` and, if enabled, `messaging_postbacks`. Subscribe to template status updates in Meta’s dashboard as required for the Page, and confirm approval there. Keep credentials out of source and screenshots. [WhatsApp setup and webhook example](https://developers.facebook.com/documentation/business-messaging/whatsapp/get-started) · [Messenger quick start](https://developers.facebook.com/docs/messenger-platform/getting-started/quick-start)

## 3. Prove the round trip

1. In Reclaim tap **Demo workspace → Connect your business**. Enter the service origin and device key, then tap **Connect workspace**. The top of the app should read **Test connection**. Verify each configured channel with a real test message.
2. Use an agreed test recipient and create an appointment **47 hours ahead**. That makes the initial reminder due on the next one-minute server check. For Messenger, have the tester send the displayed `LINK …` to the correct Page first. Keep Reclaim closed while the reminder is sent.
3. Reply **YES**, reopen the app and verify **Confirmed**. On a separate appointment, reply **NO**, check **Open slots**, and tap **Mark slot refilled** after a replacement is booked. Use separate testers or reply directly to the specific reminder when testing overlapping bookings.
4. Repeat for the other channel. Check a repeated webhook, an unavailable template, a phone offline at the due time, and a STOP reply. For WhatsApp, also test the hidden-phone variant. The service can link a business-scoped user ID from a signed delivery status to the intended recipient. [Meta’s WhatsApp identity and webhook examples](https://developers.facebook.com/documentation/business-messaging/whatsapp/business-scoped-user-ids/) An unavailable or ambiguous delivery requires owner review. Absence of a reply always keeps the original reservation.
5. Enable **Open-slot alerts** in Connection if desired. Foreground refresh runs about every 30 seconds. Android’s optional background checks run at intervals of at least 15 minutes and may be delayed by the handset. The server runs the customer-message schedule independently.

## Android ↔ service contract

All `/v1/` routes require `Authorization: Bearer <device access key>`. The phone accepts HTTPS only and declines redirects. Each installation of the pilot service has its own store and credential.

| Method and path | Purpose and response |
| --- | --- |
| `GET /v1/workspace` | Protocol 1, business name, mode, enabled channels and current appointments. Only the owner API returns booking details. |
| `PUT /v1/appointments/{UUIDv4}` | Create using name, service, channel, international recipient for WhatsApp, UTC epoch milliseconds in startsAt, IANA zone and consent=true. Retrying the same payload and ID returns the original appointment. |
| `POST /v1/appointments/{UUIDv4}/actions` | Body contains the current revision and one of confirm, release, refill or cancel. A stale change returns HTTP 409. |
| `GET /webhooks/meta`, `POST /webhooks/meta` | Subscription challenge and signed inbound events. Deduplicates provider event IDs and matches the correct sender and channel. |

The worker claims each reminder in the database before contacting Meta. It checks 48 hours before the booking, follows up once at 24 hours if at least 12 hours have passed since the first send, and flags an unanswered booking for review in its final 12 hours. An appointment created late receives the initial reminder at the next available check. The service never automatically releases an unanswered booking. A provider receipt is recorded as accepted. An uncertain send goes to review to avoid contacting the customer twice.
