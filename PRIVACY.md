# PaisaLens Privacy Notice

Last updated: 5 August 2026

PaisaLens is designed to analyze and track personal expenses entirely on the Android device where it is installed.

## Data the app accesses

If the user grants SMS permission, PaisaLens reads SMS messages to identify bank, credit-card, wallet, and UPI transaction alerts. It ignores messages recognized as OTPs, verification codes, reminders, statements, and payment requests.

SMS permission is optional. Manual transaction tracking works without it.

## How data is used

Detected transaction amount, merchant, date, source, account, direction, category, tags, and review status are used only to provide the in-app expense dashboard, activity list, budgets, recurring-payment estimates, calendar, loan tracker, analytics, and organization tools. User-entered notes, account profiles, custom categories, tags, merchant cleanup rules, loan details, and merchant category choices are used only to organize the local ledger. SMS alert text retained with a detected transaction is encrypted with a key held by Android Keystore.

If the user selects a CSV or XLSX bank statement, PaisaLens reads that file through Android's system file picker, previews recognized rows, and imports only the rows the user confirms. Statement parsing, duplicate detection, and category review happen on the device. PaisaLens does not upload the statement.

## Storage and sharing

- Data is stored only in the app's private storage on the user's device.
- PaisaLens does not transmit, sell, rent, or share financial, SMS, statement, loan, merchant, or analytics data.
- PaisaLens contains no advertising, third-party analytics, telemetry, or social SDKs.
- Automatic Android cloud backup and device-transfer backup are disabled for app data.

## Exchange-rate network access

PaisaLens declares Android's internet permission only to support an exchange-rate refresh that the user explicitly starts in **Travel mode**. It sends an HTTPS request containing only the selected three-letter currency pair to the public Frankfurter API at `api.frankfurter.dev`. No transaction amount, merchant, SMS text, statement content, account detail, app identifier, or loan detail is included in that request. Like any internet service, the rate provider and network intermediaries may receive ordinary connection metadata such as the device's IP address and request time.

Rates are cached locally and are reference rates, not guaranteed card-network or bank settlement rates. All currency conversion is performed on the device. PaisaLens does not refresh rates in the background.

## App lock and home-screen widget

If **App lock** is enabled, authentication is handled by Android's system biometric or device-credential prompt. PaisaLens does not receive or store biometric data, the device PIN, pattern, or password.

The home-screen widget hides financial amounts by default and whenever App lock is enabled. If the user explicitly enables **Show amounts on widget**, the current monthly total can be visible to anyone who can view the unlocked device's home screen.

If the user chooses **Export Excel report**, PaisaLens writes the selected ledger data to the file location chosen through Android's system file picker. The exported workbook may be stored outside the app's private storage or in a user-selected document provider. PaisaLens does not upload the workbook, and the user controls how the exported file is stored or shared afterward.

If the user chooses **Create encrypted backup**, PaisaLens creates a password-protected `.plbk` file containing the structured local ledger, budgets, account profiles, custom categories, tags, merchant rules, cleanup aliases, currency metadata, and loan trackers. The backup is encrypted on the device with AES-256-GCM using a key derived from the user's passphrase. Raw SMS message text and cached exchange-rate responses are not included. The file is written only to the location the user selects through Android's system file picker; PaisaLens does not upload it. PaisaLens cannot recover a forgotten backup passphrase.

Choosing **Restore encrypted backup** replaces the current PaisaLens ledger and related organization data with the contents of the selected backup after its passphrase has been verified.

## Retention and deletion

Data remains on the device until the user deletes it, uses **Erase all app data**, clears the app's storage in Android settings, or uninstalls PaisaLens. Files the user exported or backed up outside the app remain under the user's control and are not deleted by erasing or uninstalling PaisaLens. Erasing app data does not delete or change messages in the phone's SMS app.

## User control

The user can revoke SMS permission at any time in Android Settings. After revocation, PaisaLens stops reading the inbox and stops receiving new transaction alerts; existing local ledger data remains until the user deletes it.
