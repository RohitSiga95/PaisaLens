# PaisaLens Privacy Notice

Last updated: 4 August 2026

PaisaLens is designed to analyze and track personal expenses entirely on the Android device where it is installed.

## Data the app accesses

If the user grants SMS permission, PaisaLens reads SMS messages to identify bank, credit-card, wallet, and UPI transaction alerts. It ignores messages recognized as OTPs, verification codes, reminders, statements, and payment requests.

SMS permission is optional. Manual transaction tracking works without it.

## How data is used

Detected transaction amount, merchant, date, source, account hint, direction, and category are used only to provide the in-app expense dashboard, activity list, and budgets. SMS alert text retained with a detected transaction is encrypted with a key held by Android Keystore.

## Storage and sharing

- Data is stored only in the app's private storage on the user's device.
- PaisaLens does not declare Android's internet permission.
- PaisaLens does not transmit, sell, rent, or share financial or SMS data.
- PaisaLens contains no advertising, analytics, telemetry, or social SDKs.
- Android cloud backup and device-transfer backup are disabled for app data.

## Retention and deletion

Data remains on the device until the user deletes a transaction, uses **Erase all app data**, clears the app's storage in Android settings, or uninstalls PaisaLens. Erasing app data does not delete or change messages in the phone's SMS app.

## User control

The user can revoke SMS permission at any time in Android Settings. After revocation, PaisaLens stops reading the inbox and stops receiving new transaction alerts; existing local ledger data remains until the user deletes it.
