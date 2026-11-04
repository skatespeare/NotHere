# NotHere

NotHere is an Android application that automatically responds to incoming messages on WhatsApp.

## Features

- Customizable automatic reply message.
- Configurable reply frequency (unlimited, 30 minutes or 24 hours).
- Exclude individual contacts from automatic replies.
- Option to exclude group conversations.
- Activity log showing sent replies and skipped messages.
- Duplicate message detection to prevent repeated replies.
- Notification-based integration with WhatsApp.

## Requirements

- Android device.
- Android Studio for development.
- Notification access permission.
- Contacts permission for managing contact exclusions.

## How it works

NotHere listens for incoming message notifications and uses the available notification reply action to send an automatic response.

The app does not read your full message history or send messages independently of the notification system.

## Development

Built with Kotlin using Android Studio.

## Privacy

NotHere processes notifications locally on your Android device. Contact exclusions are stored locally.

## Project status

Personal Android project under active development.
