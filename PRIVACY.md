# PaisaLens Privacy Notice

Last updated: 20 August 2026

PaisaLens is designed to analyze and track personal expenses entirely on the Android device where it is installed.

## Data the app accesses

If the user grants SMS permission, PaisaLens reads SMS messages to identify bank, credit-card, wallet, and UPI transaction alerts, bank-balance snapshots, available-credit values, total credit limits, and supported credit-card statement or payment-due alerts. It ignores messages recognized as OTPs, verification codes and payment requests.

SMS permission is optional. Manual transaction tracking works without it.

## How data is used

Detected transaction amount, merchant, date, source, account, direction, category, tags, review status, duplicate-source count, account-balance history, available credit, total credit limit, fetched time, card-bill amount and due date are used only to provide the in-app dashboard, Financial Pulse, activity list, budgets, utilisation tracker, card-bill history, due-date centre, cash-flow estimates, net-worth summary, recurring-payment estimates, calendar, loan tracker, analytics, reconciliation, weekly review, data-health checks, and organization tools. User-entered notes, account profiles, balance snapshots, bill reminders, paid-bill confirmations, net-worth items, custom categories, tags, smart category rules, merchant cleanup rules, loan details, monthly reconciliation values, transaction links, named participant expense shares and percentages, reimbursements, savings goals and contributions, subscription records, UPI AutoPay planning records, and merchant category choices are used only to organize the local ledger and run on-device calculations. Home layout choices and Activity saved views—including a user-chosen view name, search text and selected filter values—are stored in app-private preferences and remain on the device. PaisaLens does not move savings, pay a bill, request reimbursements, create mandates, or initiate a UPI transaction. Supported changes create a local audit record so the user can inspect and undo eligible batches. What-if scenarios are calculated in memory and do not change stored transactions or balances. SMS alert text retained with a detected transaction is encrypted with a key held by Android Keystore. Repeated copies of the same supported alert are consolidated into one transaction while retaining local source identifiers and a duplicate count. For a balance-only reply, PaisaLens stores the extracted values, sender label, account match, and timestamp—not the full reply text.

The **SMS Coverage Centre** may retain an unsupported message that appears likely to be financial so the user can review local parser coverage. Its message body is encrypted using Android Keystore; the sender label, reason, status and timestamps are stored in app-private SQLite. Coverage candidates remain only on this device, survive a portable-ledger restore, and are never included in a portable backup. The user can dismiss or delete a candidate, or create a narrow literal sender-and-required-phrase rule. PaisaLens does not learn a rule on a server, execute downloaded code, or upload the message. OTPs and verification codes are excluded before coverage review.

If the user taps an account refresh button, PaisaLens opens the phone's SMS app with a verified bank service number and enquiry keyword filled in where supported. PaisaLens does not send the SMS automatically, does not request Android's `SEND_SMS` permission, and cannot prevent carrier SMS charges. The user reviews and sends the message in the SMS app.

If the user chooses **UPI check** for a bank account, PaisaLens can open the normal home screen of a supported UPI app installed on the device. PaisaLens sends no account identifier, balance, payment request, UPI PIN, or other financial data to that app. Android does not return the displayed balance to PaisaLens, and PaisaLens does not read the other app's screen, notifications, clipboard, accessibility content, or private storage. After returning, the user may type the balance they personally saw. PaisaLens saves that amount, the selected app label where available, the account match, and the time as a clearly marked **user-entered** balance-history snapshot. It is not represented as a bank-verified or live API value. PaisaLens checks only a narrow list of known UPI app package names and does not request broad visibility into all installed apps.

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

## App lock and home-screen widgets

If **App lock** is enabled, authentication is handled by Android's system biometric or device-credential prompt. PaisaLens does not receive or store biometric data, the device PIN, pattern, or password.

The home-screen widgets can show a current-month snapshot, category breakdown, due bills, and credit-card bill totals. They hide financial amounts by default and whenever App lock, the saved privacy default, or an active hide-amounts session is enabled. If the user explicitly enables **Show amounts on widget** while those protections are off, those values can be visible to anyone who can view the unlocked device's home screen.

## One-tap privacy mode

The Home eye control can temporarily mask supported monetary values throughout the running app. The user can also make privacy mode the next-launch default. If **Protect screenshots and app previews** is enabled, PaisaLens asks Android to block screenshots, screen recordings and the Recents preview while privacy mode is active. Android and device-manufacturer behavior may vary.

Privacy mode changes presentation only. It does not delete, encrypt again, alter, or stop calculations on stored financial data, and it does not hide non-amount context such as merchant or account names. A temporary session override ends when the app process ends; the configured default remains until the user changes it.

## Private notification digest

If the user explicitly enables **Private notification digest**, PaisaLens schedules an inexact daily or weekly Android notification using only data already stored on the device. The digest may summarize recently recorded expenses, unresolved reviews, upcoming bills or payment commitments, and savings goals that need attention. Amounts are omitted by default and are included only if the user turns on **Show amounts**. The notification's public lock-screen version is always generic and contains no amount, merchant, account, participant, goal, or payment name. Android notification permission is requested only when the user enables this feature, and the feature can be disabled or its permission revoked at any time.

Android may deliver an inexact digest near the selected hour to protect battery life. No server, cloud scheduler, or network request is used for digest generation or delivery.

## Private actionable alerts

If the user explicitly enables **Private actionable alerts**, PaisaLens performs an inexact daily on-device check using the local ledger. Configurable categories can cover upcoming card bills and other payments, Budgeting 2.0 warning levels, high credit utilisation, a low 14-day cash-flow projection, overdue split reimbursements, and other Needs Your Attention items. The user controls the enabled categories, thresholds, check hour, amount visibility and lock-screen behavior.

Amounts are hidden and the public lock-screen version is generic by default. Notifications are marked local-only, use only allow-listed in-app destinations, and keep a small app-private delivery history to suppress repeated alerts for the configured interval while still allowing a priority escalation. No server, cloud scheduler, account API, or network request is used to evaluate or deliver these alerts. Disabling alerts cancels their scheduled check and visible alert notification; **Erase all app data** also clears their settings and repeat-suppression history.

If the user chooses **Export Excel report**, PaisaLens writes the selected ledger data to the file location chosen through Android's system file picker. The exported workbook may be stored outside the app's private storage or in a user-selected document provider. PaisaLens does not upload the workbook, and the user controls how the exported file is stored or shared afterward.

If the user chooses **Create encrypted backup**, PaisaLens creates a password-protected `.plbk` file containing the structured local ledger, budgets, account profiles, balance history, bill reminders and card-bill records, net-worth items, custom categories, tags, merchant and smart category rules, SMS Coverage rules, cleanup aliases, loan trackers, reconciliation records, transaction links, expense splits, savings goals and contributions, subscriptions and UPI AutoPay planning records, and audit history. The backup is encrypted on the device with AES-256-GCM using a key derived from the user's passphrase. Raw SMS message text, SMS Coverage candidate bodies, and cached exchange-rate responses are not included. The file is written only to the location the user selects through Android's system file picker; PaisaLens does not upload it. PaisaLens cannot recover a forgotten backup passphrase.

If the user enables **Scheduled encrypted backups**, PaisaLens wraps the chosen backup passphrase with a non-exportable Android Keystore key, schedules inexact on-device work, and writes verified encrypted copies only to the Android document folder the user grants. A user-selected document provider may itself be local, removable, or cloud-backed; that storage choice is controlled by the user and provider, not by PaisaLens. PaisaLens retains only the configured number of its own named backup files when the provider permits deletion and reports a warning rather than deleting unrelated files. Disabling the schedule stops future runs. Forgetting the stored password makes future scheduled backups unavailable until a new password is saved; it does not decrypt or remove previously created files.

Choosing **Verify encrypted backup** authenticates and decodes the selected backup in memory, reports its format, checksum, creation time, and record counts, and does not write any backup contents to the local ledger. PaisaLens remembers only the time of the successful verification in preferences; the passphrase is cleared from memory after the attempt.

Choosing **Restore encrypted backup** replaces the portable PaisaLens ledger and related organization data with the contents of the selected backup after its passphrase has been verified. Device-only SMS Coverage candidates are preserved because their message bodies are deliberately excluded from backup files.

## Retention and deletion

Data remains on the device until the user deletes it, uses **Erase all app data**, clears the app's storage in Android settings, or uninstalls PaisaLens. **Erase all app data** also clears Home personalization, Activity saved views, actionable-alert settings and their local repeat-suppression history. Files the user exported or backed up outside the app remain under the user's control and are not deleted by erasing or uninstalling PaisaLens. Erasing app data does not delete or change messages in the phone's SMS app.

## User control

The user can revoke SMS or notification permission at any time in Android Settings. After SMS revocation, PaisaLens stops reading the inbox and stops receiving new transaction alerts; after notification revocation, the private digest and private actionable alerts can no longer be shown. Existing local ledger data remains until the user deletes it.
