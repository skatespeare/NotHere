# WhatsApp AutoReply 2.2 — English version

Default automatic reply:

> Thanks for your message. I'll get back to you as soon as I can!

## Features
- Detects notifications from the standard WhatsApp Android app.
- Sends the fixed reply using the reply action exposed by a WhatsApp notification.
- Enable/disable automatic replies.
- Excludes group chats by default (heuristic detection; configurable).
- Skips recognisable outgoing-message notifications (best-effort detection).
- Local activity log with the latest 100 events; refresh and clear controls.
- One automatic reply per conversation every 30 minutes.
- Warns if notification access has not been granted and reminds users to keep WhatsApp notifications enabled.

## Open and run
1. Extract this ZIP file.
2. In Android Studio, select **Open** and choose the `WhatsAppAutoReply-v2` folder.
3. Allow Gradle to sync. Use JDK 17 if prompted.
4. Connect an Android phone using USB. Enable Developer options and USB debugging if needed.
5. Press **Run**.
6. Open the app, tap **Set up notification access**, and grant access.
7. Return to the app and enable automatic replies.
8. Test with a separate WhatsApp account before relying on the app.

## Important limitations
This prototype uses Android `NotificationListenerService` and the `RemoteInput` reply action that WhatsApp may expose in a notification. It is not an official WhatsApp API integration.

The app can check whether Android notification access has been granted to WhatsApp AutoReply. Android does not provide a reliable general mechanism for this app to determine whether WhatsApp notifications themselves have been disabled. Keep WhatsApp notifications enabled; sounds and vibration may be turned off.

Group detection and outgoing-message detection are heuristic and are not guaranteed to be correct for every WhatsApp version or notification format. Test carefully before using the app unattended.

This version supports standard WhatsApp (`com.whatsapp`), not WhatsApp Business. There is no INTERNET permission; notification content and the activity log remain on the device.
