<p align="center">
  <img src="docs/paisalens-banner.svg" alt="PaisaLens — private, offline expense intelligence for Android" width="100%">
</p>

<p align="center">
  <a href="https://github.com/RohitSiga95/PaisaLens/releases/tag/v1.4.0"><img alt="Release v1.4.0" src="https://img.shields.io/badge/release-v1.4.0-5965E8?style=for-the-badge"></a>
  <img alt="Source v1.4.0" src="https://img.shields.io/badge/source-v1.4.0-1E88E5?style=for-the-badge">
  <img alt="Android 8.0+" src="https://img.shields.io/badge/Android-8.0%2B-21D19F?style=for-the-badge&logo=android&logoColor=white">
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-Jetpack_Compose-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white">
  <img alt="Private by design" src="https://img.shields.io/badge/network-rate_refresh_only-0A4E3C?style=for-the-badge&logo=shield&logoColor=white">
</p>

<p align="center">
  A private Android expense tracker for Indian bank, credit-card, wallet and UPI alerts.<br>
  Your SMS and financial data are processed entirely on your phone.
</p>

<p align="center">
  <a href="https://github.com/RohitSiga95/PaisaLens/releases/download/v1.4.0/PaisaLens-v1.4.0-debug.apk"><strong>⬇ Download PaisaLens v1.4.0 APK</strong></a>
  &nbsp;·&nbsp;
  <a href="https://github.com/RohitSiga95/PaisaLens/releases/tag/v1.4.0">View release notes</a>
  &nbsp;·&nbsp;
  <a href="PRIVACY.md">Privacy notice</a>
</p>

> [!IMPORTANT]
> The downloadable file is the current **debug APK** for direct installation and testing. Android may ask you to allow installation from your browser or file manager. Requires Android 8.0 (API 26) or newer.

## See PaisaLens in action

<table>
  <tr>
    <td align="center"><strong>Dashboard &amp; merchant grouping</strong></td>
    <td align="center"><strong>Searchable activity &amp; notes</strong></td>
    <td align="center"><strong>Private Excel export</strong></td>
  </tr>
  <tr>
    <td><img src="docs/screenshots/home.png" alt="PaisaLens dashboard showing spending and merchant category prompt" width="260"></td>
    <td><img src="docs/screenshots/activity.png" alt="PaisaLens activity screen showing searchable expenses and notes" width="260"></td>
    <td><img src="docs/screenshots/settings.png" alt="PaisaLens settings screen with offline privacy and Excel export" width="260"></td>
  </tr>
</table>

<sub>Preview screens mirror the current Compose interface; the example transactions and totals are sample data.</sub>

## Your spending, made understandable

| | Feature | What it does |
|---|---|---|
| 🔎 | **Local SMS detection** | Finds debits, credits, refunds, card purchases, ATM withdrawals, UPI and wallet alerts without uploading messages. |
| 🏷️ | **Merchant category rules** | Groups matching merchants, asks you once for the category, updates their existing expenses and remembers the answer. |
| 📝 | **Expense notes** | Adds a specific note to a deducted transaction and shows it beneath the item in recent activity and search results. |
| 📊 | **Visual dashboard** | Shows monthly spend, remaining budget, category breakdown and useful insights at a glance. |
| 🎯 | **Category budgets** | Tracks limits with progress and overspend states. |
| 📗 | **Beautiful Excel export** | Creates a categorized workbook with transactions, trends, budgets, dashboard cards and native charts. |
| 🏦 | **Accounts & cards** | Organizes transactions into bank accounts, credit cards, wallets, cash, or custom account profiles. |
| 🔁 | **Recurring payments** | Detects consistent weekly and monthly expenses and estimates the next due date. |
| 🧩 | **Custom categories & tags** | Adds personal categories and searchable tags beyond the built-in category set. |
| ✅ | **Review inbox** | Holds uncertain merchant or category matches for confirmation before they affect analytics. |
| 🔐 | **Encrypted backup & restore** | Creates a password-protected portable backup locally and restores it when needed. |
| ✍️ | **Manual entry** | Records an expense, income, refund, or transfer when an SMS is unavailable. |
| 🌓 | **Material 3 design** | Supports light and dark themes with responsive Jetpack Compose interactions. |
| 🧹 | **Merchant cleanup** | Renames or merges inconsistent merchants and applies the cleanup rule to future SMS and statement imports. |
| 🏠 | **Home-screen widget** | Shows a privacy-aware monthly glance, with amounts hidden by default and whenever App lock is active. |
| 🔒 | **App lock** | Uses Android's system fingerprint, face, PIN, pattern, or password prompt to protect the ledger. |
| 📥 | **Statement import** | Previews and imports common CSV and XLSX bank statements locally with duplicate protection. |
| 📈 | **Better analytics** | Adds six-month trends, projections, top merchants, category rankings, and exact-value summaries. |
| 📅 | **Calendar view** | Browses confirmed expenses day by day and opens the underlying transactions. |
| 🏦 | **EMI & loan tracker** | Calculates reducing-balance EMI, tracks paid installments, progress, and next due dates. |
| ✈️ | **Travel mode** | Stores original foreign amounts and converts them with an explicitly refreshed HTTPS reference rate. |
| ✨ | **On-device insights** | Detects possible duplicates, unusual charges, price increases, spending pace, and concentration without uploading data. |

### Excel reports that are ready to explore

The workbook is generated entirely offline and saved only where you choose through Android's system file picker.

<p align="center">
  <img src="docs/screenshots/excel-dashboard.png" alt="Excel workbook exported by PaisaLens with category and monthly spend charts" width="900">
</p>

## Private by design

```text
SMS / statement  →  on-device parser  →  encrypted local ledger  →  dashboard / Excel export
                                       │                         ↘ encrypted manual backup
currency pair only  →  HTTPS reference-rate API  →  cached rate ↗
                                       ✕ no account
                                       ✕ no telemetry
                                       ✕ no financial-data upload
```

- No account, ads, third-party analytics, telemetry or cloud SDKs.
- `INTERNET` is used only after the user taps a Travel-mode rate refresh; the HTTPS request contains only the currency pair.
- SMS, statements, transactions, merchants, loans, categories, notes, tags, budgets, and insights stay on the device.
- Automatic cloud backup and device-transfer backup are disabled.
- Optional manual backups are encrypted on-device with a user passphrase, exclude raw SMS text, and are saved only where the user chooses.
- SMS is read only after explicit permission and disclosure.
- OTPs, verification codes, reminders, statements and collect requests are ignored.
- Stored SMS alert text is encrypted with AES-GCM using a non-exportable Android Keystore key.
- Transactions, notes, account profiles, custom categories, tags, merchant rules, cleanup aliases, loans, and cached rates use app-private platform SQLite storage.
- **Erase all** clears the local PaisaLens ledger and organization data without changing the phone's SMS inbox or previously exported files.

Read the complete [plain-language privacy notice](PRIVACY.md).

## Download and install

1. Download **[PaisaLens-v1.4.0-debug.apk](https://github.com/RohitSiga95/PaisaLens/releases/download/v1.4.0/PaisaLens-v1.4.0-debug.apk)** on your Android phone.
2. Open the downloaded file.
3. If Android prompts you, allow your browser or file manager to install unknown apps.
4. Choose **Install**, then open PaisaLens and review the SMS disclosure before granting access.

You can also browse the [v1.4.0 release page](https://github.com/RohitSiga95/PaisaLens/releases/tag/v1.4.0).

**APK SHA-256**

```text
7fc9586eda65c88bc466dd74633d69b6219f8e38753e867c07b659179cdb0cca
```

## Build it yourself

Requirements: JDK 17, Android SDK Platform 36 and Android SDK Build Tools 35 or newer.

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

The generated v1.4.0 APK will be available at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Install it on a USB-connected Android device with:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

<details>
<summary><strong>Project structure</strong></summary>

```text
app/src/main/java/com/paisalens/app/
├── data/
│   ├── local/          # SQLite and preferences
│   ├── model/          # Transaction and budget models
│   ├── parser/         # Bank, card and UPI SMS parser
│   ├── importer/       # Local CSV/XLSX statement import
│   ├── network/        # Explicit HTTPS reference-rate refresh only
│   ├── backup/         # Password-protected portable backups
│   ├── export/         # Offline Excel workbook generation
│   └── repository/     # Local data coordination
├── security/           # Android Keystore AES-GCM
├── sms/                # Inbox scanner and incoming SMS receiver
├── widget/             # Privacy-aware Android home-screen widget
└── ui/                 # Compose theme, components and screens
```

</details>

## SMS permission and Play distribution

`READ_SMS` and `RECEIVE_SMS` are restricted Google Play permissions. SMS-based money management is listed as an eligible exception, but public Play distribution still requires a Permissions Declaration, prominent disclosure, consent, a privacy policy and Google Play review. A directly installed APK does not go through Play review, but PaisaLens intentionally keeps the runtime permission and disclosure.

[Read Google's SMS and Call Log permission policy](https://support.google.com/googleplay/android-developer/answer/10208820).

## Accuracy note

Banks and payment providers use many SMS formats. PaisaLens uses deterministic on-device rules and covers common Indian formats, but you should still verify detected transactions and recategorize them when needed. PaisaLens never initiates payments or accesses a bank account.

---

<p align="center">
  <strong>PaisaLens</strong> · Your money stays yours.
</p>
