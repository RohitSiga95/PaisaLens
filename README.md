# PaisaLens

PaisaLens is a native, offline-first Android expense tracker for Indian bank, credit-card, wallet, and UPI transaction alerts.

## What is included

- Local SMS transaction detection for debits, credits, refunds, card purchases, ATM withdrawals, UPI, and wallet alerts
- Automatic categories for food, groceries, shopping, transport, bills, entertainment, health, education, travel, cash, and transfers
- Merchant-wide category rules that update matching expenses and remember the choice for future alerts
- Optional per-expense notes shown directly in recent activity and searchable transaction history
- Offline Excel export with a formatted dashboard, category analysis, monthly trends, budgets, and native charts
- Duplicate-safe imports for up to 10,000 inbox messages
- Automatic analysis of new transaction SMS after permission is granted
- Monthly dashboard with animated spending breakdown and useful insights
- Searchable/filterable transaction activity
- Category budgets with progress and overspend states
- Manual expense, income, and refund entry when SMS access is unavailable
- Light and dark themes with responsive Material 3 interactions

## Privacy design

- **No INTERNET permission.** The app has no network capability.
- **No account, ads, analytics, telemetry, or cloud SDKs.**
- **Cloud backup and device-transfer backup are disabled.**
- **SMS is read only after explicit user consent.**
- **OTPs, verification codes, payment reminders, statements, and collect requests are ignored.**
- **SMS alert bodies saved for the local ledger are encrypted with AES-GCM using a non-exportable Android Keystore key.**
- **Transactions, merchant category rules, notes, and budgets use app-private platform SQLite storage.**
- **Excel files are created only when requested and saved through Android's system file picker.**
- **Erase all** deletes the local ledger and budgets without modifying the phone's original SMS inbox.

See [PRIVACY.md](PRIVACY.md) for the plain-language privacy notice.

## Project structure

    app/src/main/java/com/paisalens/app/
    ├── data/
    │   ├── local/          # SQLite and preferences
    │   ├── model/          # Transaction and budget models
    │   ├── parser/         # Bank/card/UPI SMS parser
    │   └── repository/     # Local data coordination
    ├── security/           # Android Keystore AES-GCM
    ├── sms/                # Inbox scanner and incoming SMS receiver
    └── ui/                 # Compose theme, components, and screens

## Build and test

Requirements:

- JDK 17
- Android SDK Platform 36
- Android SDK Build Tools 35+

From this folder:

    ./gradlew testDebugUnitTest lintDebug assembleDebug

The installable debug APK is generated at:

    app/build/outputs/apk/debug/app-debug.apk

Install on a USB-connected Android device:

    adb install -r app/build/outputs/apk/debug/app-debug.apk

## SMS permission and Play distribution

READ_SMS and RECEIVE_SMS are restricted Google Play permissions. SMS-based money management is listed as an eligible exception, but public Play distribution still requires a Permissions Declaration, prominent disclosure, consent, a privacy policy, and Google Play review. A locally installed APK does not go through Play review, but the runtime permission and disclosure remain intentionally present.

Official policy: <https://support.google.com/googleplay/android-developer/answer/10208820>

## Accuracy note

Banks and payment providers use many SMS formats. PaisaLens uses deterministic on-device rules and includes coverage for common Indian formats, but users should verify detected transactions and can recategorize them. The parser never initiates a payment or accesses a bank account.
