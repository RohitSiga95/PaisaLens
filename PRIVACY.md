# PaisaLens Privacy Notice

Last updated: 12 August 2026

PaisaLens is designed to analyze and track personal expenses entirely on the Android device where it is installed.

## Data the app accesses

If the user grants SMS permission, PaisaLens reads SMS messages to identify bank, credit-card, wallet, and UPI transaction alerts, bank-balance snapshots, available-credit values, and total credit limits included in bank replies. It ignores messages recognized as OTPs, verification codes, reminders, statements, and payment requests.

SMS permission is optional. Manual transaction tracking works without it.

## How data is used

Detected transaction amount, merchant, date, source, account, direction, category, tags, review status, account-balance history, available credit, total credit limit, and fetched time are used only to provide the in-app dashboard, activity list, budgets, utilisation tracker, due-date centre, cash-flow estimates, net-worth summary, recurring-payment estimates, calendar, loan tracker, analytics, reconciliation, data-health checks, and organization tools. User-entered notes, account profiles, bill reminders, net-worth items, custom categories, tags, smart category rules, merchant cleanup rules, loan details, monthly reconciliation values, transaction links, participant expense shares, reimbursements, savings goals and contributions, subscription records, UPI AutoPay planning records, and merchant category choices are used only to organize the local ledger and run on-device calculations. PaisaLens does not move savings, request reimbursements, create mandates, or contact a bank or UPI app. Supported changes create a local audit record so the user can inspect and undo eligible batches. What-if scenarios are calculated in memory and do not change stored transactions or balances. SMS alert text retained with a detected transaction is encrypted with a key held by Android Keystore. For a balance-only reply, PaisaLens stores the extracted values, sender label, account match, and timestamp—not the full reply text.

If the user taps an account refresh button, PaisaLens opens the phone's SMS app with a verified bank service number and enquiry keyword filled in where supported. PaisaLens does not send the SMS automatically, does not request Android's `SEND_SMS` permission, and cannot prevent carrier SMS charges. The user reviews and sends the message in the SMS app.

If the user captures or selects a bill image, PaisaLens runs a bundled text-recognition model on the device and uses the result to prefill an editable manual transaction. The image and recognized text are not uploaded. Camera captures use a temporary app-cache file that is deleted after processing; selected images remain in their original user-controlled location. PaisaLens stores only the transaction details the user reviews and saves.

If the user selects a CSV or XLSX bank or credit-card statement, PaisaLens reads that file through Android's system file picker. It can preview recognized rows for import or audit reviewed credit-card rows against locally stored SMS transactions. Statement parsing, totals, fee/interest/GST classification, matching, duplicate detection, and category review happen on the device. PDF layouts are not guessed automatically; the app transparently asks for reviewed summary values and tabular rows or a CSV/XLSX export. PaisaLens does not upload the statement or its metadata.

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

## Private notification digest

If the user explicitly enables **Private notification digest**, PaisaLens schedules an inexact daily or weekly Android notification using only data already stored on the device. The digest may summarize recently recorded expenses, unresolved reviews, upcoming bills or payment commitments, and savings goals that need attention. Amounts are omitted by default and are included only if the user turns on **Show amounts**. The notification's public lock-screen version is always generic and contains no amount, merchant, account, participant, goal, or payment name. Android notification permission is requested only when the user enables this feature, and the feature can be disabled or its permission revoked at any time.

Android may deliver an inexact digest near the selected hour to protect battery life. No server, cloud scheduler, or network request is used for digest generation or delivery.

If the user chooses **Export Excel report**, PaisaLens writes the selected ledger data to the file location chosen through Android's system file picker. The exported workbook may be stored outside the app's private storage or in a user-selected document provider. PaisaLens does not upload the workbook, and the user controls how the exported file is stored or shared afterward.

If the user chooses **Create encrypted backup**, PaisaLens creates a password-protected `.plbk` file containing the structured local ledger, budgets, account profiles, balance history, bill reminders, net-worth items, custom categories, tags, merchant and smart category rules, cleanup aliases, loan trackers, reconciliation records, transaction links, expense splits, savings goals and contributions, subscriptions and UPI AutoPay planning records, and audit history. The backup is encrypted on the device with AES-256-GCM using a key derived from the user's passphrase. Raw SMS message text and cached exchange-rate responses are not included. The file is written only to the location the user selects through Android's system file picker; PaisaLens does not upload it. PaisaLens cannot recover a forgotten backup passphrase.

Choosing **Verify encrypted backup** authenticates and decodes the selected backup in memory, reports its format, checksum, creation time, and record counts, and does not write any backup contents to the local ledger. PaisaLens remembers only the time of the successful verification in preferences; the passphrase is cleared from memory after the attempt.

Choosing **Restore encrypted backup** replaces the current PaisaLens ledger and related organization data with the contents of the selected backup after its passphrase has been verified.

## Retention and deletion

Data remains on the device until the user deletes it, uses **Erase all app data**, clears the app's storage in Android settings, or uninstalls PaisaLens. Files the user exported or backed up outside the app remain under the user's control and are not deleted by erasing or uninstalling PaisaLens. Erasing app data does not delete or change messages in the phone's SMS app.

## User control

The user can revoke SMS or notification permission at any time in Android Settings. After SMS revocation, PaisaLens stops reading the inbox and stops receiving new transaction alerts; after notification revocation, the private digest can no longer be shown. Existing local ledger data remains until the user deletes it.
