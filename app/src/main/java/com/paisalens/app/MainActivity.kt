package com.paisalens.app

import android.Manifest
import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.viewModels
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.paisalens.app.ui.PaisaLensApp
import com.paisalens.app.ui.PaisaLensViewModel
import com.paisalens.app.ui.AppLockScreen
import com.paisalens.app.ui.UpiAppChoice
import com.paisalens.app.ui.theme.PaisaLensTheme
import com.paisalens.app.data.model.AccountProfile
import com.paisalens.app.data.model.StatementAuditMetadata
import com.paisalens.app.notification.PrivateDigestNotifier
import com.paisalens.app.notification.ActionableAlertNotifier
import com.paisalens.app.notification.AlertDestination
import com.paisalens.app.sms.BankSmsSupport
import com.paisalens.app.widget.WIDGET_DESTINATION_EXTRA
import java.io.File
import java.time.LocalDate

class MainActivity : FragmentActivity() {
    private val viewModel: PaisaLensViewModel by viewModels {
        PaisaLensViewModel.Factory(application as PaisaLensApplication)
    }

    private var hasSmsPermission by mutableStateOf(false)
    private var hasNotificationPermission by mutableStateOf(false)
    private var hasActionableAlertPermission by mutableStateOf(false)
    private var pendingNotificationPurpose: NotificationPermissionPurpose? = null
    private var pendingBackupPassphrase: CharArray? = null
    private var pendingRestorePassphrase: CharArray? = null
    private var pendingVerifyPassphrase: CharArray? = null
    private var pendingStatementAccountId: Long? = null
    private var pendingStatementAuditMetadata: StatementAuditMetadata? = null
    private var pendingReceiptUri: Uri? = null
    private var availableUpiApps by mutableStateOf<List<UpiAppChoice>>(emptyList())
    private var isUnlocked by mutableStateOf(true)
    private var widgetDestination by mutableStateOf<String?>(null)
    private var alertDestination by mutableStateOf<AlertDestination?>(null)
    private var authPromptVisible = false
    private var authenticationPurpose = AuthenticationPurpose.UNLOCK

    private val smsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        hasSmsPermission = grants[Manifest.permission.READ_SMS] == true
        if (hasSmsPermission) {
            viewModel.scanSms()
        } else {
            Toast.makeText(
                this,
                "SMS access is needed to resync bank and card alerts",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ ->
        updateNotificationPermissionState()
        if (!completePendingNotificationPermission()) {
            pendingNotificationPurpose = null
            Toast.makeText(this, "Notifications remain off. You can enable them later in Android Settings.", Toast.LENGTH_LONG).show()
        }
    }

    private val workbookExportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        ),
    ) { uri ->
        uri?.let { viewModel.exportWorkbook(contentResolver, it) }
    }

    private val backupExportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val passphrase = pendingBackupPassphrase
        pendingBackupPassphrase = null
        if (uri != null && passphrase != null) {
            viewModel.exportBackup(contentResolver, uri, passphrase)
        } else {
            passphrase?.fill('\u0000')
        }
    }

    private val backupRestoreLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val passphrase = pendingRestorePassphrase
        pendingRestorePassphrase = null
        if (uri != null && passphrase != null) {
            viewModel.restoreBackup(contentResolver, uri, passphrase)
        } else {
            passphrase?.fill('\u0000')
        }
    }

    private val backupVerifyLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val passphrase = pendingVerifyPassphrase
        pendingVerifyPassphrase = null
        if (uri != null && passphrase != null) {
            viewModel.verifyBackup(contentResolver, uri, passphrase)
        } else {
            passphrase?.fill('\u0000')
        }
    }

    private val statementImportLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val accountId = pendingStatementAccountId
        pendingStatementAccountId = null
        uri?.let { viewModel.previewStatement(contentResolver, it, accountId) }
    }

    private val statementAuditLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val metadata = pendingStatementAuditMetadata
        pendingStatementAuditMetadata = null
        if (uri != null && metadata != null) {
            viewModel.auditCardStatement(contentResolver, uri, metadata)
        }
    }

    private val receiptCameraLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { captured ->
        val uri = pendingReceiptUri
        pendingReceiptUri = null
        if (captured && uri != null) viewModel.recognizeReceipt(uri, "camera", deleteAfterProcessing = true)
    }

    private val receiptPickerLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        uri?.let { viewModel.recognizeReceipt(it, "uploaded bill") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingNotificationPurpose = savedInstanceState
            ?.getString(STATE_PENDING_NOTIFICATION_PURPOSE)
            ?.let { stored -> NotificationPermissionPurpose.entries.firstOrNull { it.name == stored } }
        enableEdgeToEdge()
        updatePermissionState()
        refreshAvailableUpiApps()
        widgetDestination = intent?.getStringExtra(WIDGET_DESTINATION_EXTRA)
        alertDestination = intent?.takeIf { it.hasExtra(ActionableAlertNotifier.EXTRA_DESTINATION) }
            ?.let(ActionableAlertNotifier::requestedDestination)
        isUnlocked = !(application as PaisaLensApplication).preferences.appLockEnabled

        setContent {
            val themeConfiguration by viewModel.themeConfiguration.collectAsStateWithLifecycle()
            var upiBalanceAccountId by rememberSaveable { mutableStateOf<Long?>(null) }
            var selectedUpiPackageName by rememberSaveable { mutableStateOf<String?>(null) }
            var upiBalanceDraft by rememberSaveable { mutableStateOf("") }
            var upiBalanceSaving by rememberSaveable { mutableStateOf(false) }
            var upiBalanceError by rememberSaveable { mutableStateOf<String?>(null) }
            if (isUnlocked) {
                PaisaLensApp(
                    viewModel = viewModel,
                    initialWidgetDestination = widgetDestination,
                    onWidgetDestinationHandled = {
                        widgetDestination = null
                        intent?.removeExtra(WIDGET_DESTINATION_EXTRA)
                    },
                    initialAlertDestination = alertDestination,
                    onAlertDestinationHandled = {
                        alertDestination = null
                        intent?.removeExtra(ActionableAlertNotifier.EXTRA_DESTINATION)
                    },
                    hasSmsPermission = hasSmsPermission,
                    hasNotificationPermission = hasNotificationPermission,
                    hasActionableAlertPermission = hasActionableAlertPermission,
                    onRequestSmsPermission = {
                        smsPermissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.READ_SMS,
                                Manifest.permission.RECEIVE_SMS,
                            ),
                        )
                    },
                    onRequestDigestNotificationPermission = {
                        requestNotificationPermission(NotificationPermissionPurpose.DIGEST)
                    },
                    onRequestActionableAlertPermission = {
                        requestNotificationPermission(NotificationPermissionPurpose.ACTIONABLE_ALERTS)
                    },
                    onOpenActionableAlertSettings = {
                        startActivity(ActionableAlertNotifier.settingsIntent(this))
                    },
                    onExportData = {
                        workbookExportLauncher.launch("PaisaLens-${LocalDate.now()}.xlsx")
                    },
                    onCreateBackup = { passphrase ->
                        pendingBackupPassphrase?.fill('\u0000')
                        pendingBackupPassphrase = passphrase
                        backupExportLauncher.launch("PaisaLens-backup-${LocalDate.now()}.plbk")
                    },
                    onRestoreBackup = { passphrase ->
                        pendingRestorePassphrase?.fill('\u0000')
                        pendingRestorePassphrase = passphrase
                        backupRestoreLauncher.launch(arrayOf("application/octet-stream", "*/*"))
                    },
                    onVerifyBackup = { passphrase ->
                        pendingVerifyPassphrase?.fill('\u0000')
                        pendingVerifyPassphrase = passphrase
                        backupVerifyLauncher.launch(arrayOf("application/octet-stream", "*/*"))
                    },
                    onImportStatement = { accountId ->
                        pendingStatementAccountId = accountId
                        statementImportLauncher.launch(
                            arrayOf(
                                "text/csv",
                                "text/comma-separated-values",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                "application/octet-stream",
                            ),
                        )
                    },
                    onAuditCardStatement = { metadata ->
                        pendingStatementAuditMetadata = metadata
                        statementAuditLauncher.launch(
                            arrayOf(
                                "text/csv",
                                "text/comma-separated-values",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                "application/octet-stream",
                            ),
                        )
                    },
                    onAppLockChange = ::changeAppLock,
                    onCaptureReceipt = ::captureReceipt,
                    onPickReceipt = {
                        receiptPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    onComposeBalanceSms = ::composeBalanceSms,
                    availableUpiApps = availableUpiApps,
                    upiBalanceAccountId = upiBalanceAccountId,
                    selectedUpiPackageName = selectedUpiPackageName,
                    upiBalanceDraft = upiBalanceDraft,
                    upiBalanceSaving = upiBalanceSaving,
                    upiBalanceError = upiBalanceError,
                    onUpiBalanceAccountChange = { accountId -> upiBalanceAccountId = accountId },
                    onSelectedUpiPackageChange = { packageName -> selectedUpiPackageName = packageName },
                    onUpiBalanceDraftChange = { draft -> upiBalanceDraft = draft },
                    onUpiBalanceSavingChange = { saving -> upiBalanceSaving = saving },
                    onUpiBalanceErrorChange = { error -> upiBalanceError = error },
                    onLaunchUpiApp = ::launchUpiApp,
                )
            } else {
                PaisaLensTheme(configuration = themeConfiguration) {
                    AppLockScreen(onUnlock = { authenticate(AuthenticationPurpose.UNLOCK) })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        widgetDestination = intent.getStringExtra(WIDGET_DESTINATION_EXTRA)
        alertDestination = intent.takeIf { it.hasExtra(ActionableAlertNotifier.EXTRA_DESTINATION) }
            ?.let(ActionableAlertNotifier::requestedDestination)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pendingNotificationPurpose?.let {
            outState.putString(STATE_PENDING_NOTIFICATION_PURPOSE, it.name)
        }
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        updatePermissionState()
        completePendingNotificationPermission()
        refreshAvailableUpiApps()
        if (!isUnlocked && (application as PaisaLensApplication).preferences.appLockEnabled) {
            window.decorView.post { authenticate(AuthenticationPurpose.UNLOCK) }
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations && (application as PaisaLensApplication).preferences.appLockEnabled) {
            isUnlocked = false
        }
    }

    private fun updatePermissionState() {
        hasSmsPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.READ_SMS,
        ) == PackageManager.PERMISSION_GRANTED
        updateNotificationPermissionState()
    }

    private fun updateNotificationPermissionState() {
        hasNotificationPermission = PrivateDigestNotifier.canPost(this)
        hasActionableAlertPermission = ActionableAlertNotifier.canPost(this)
    }

    private fun requestNotificationPermission(purpose: NotificationPermissionPurpose) {
        pendingNotificationPurpose = purpose
        updateNotificationPermissionState()
        if (completePendingNotificationPermission()) return

        val runtimeGranted = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        if (runtimeGranted) {
            val settingsIntent = when (purpose) {
                NotificationPermissionPurpose.DIGEST -> PrivateDigestNotifier.settingsIntent(this)
                NotificationPermissionPurpose.ACTIONABLE_ALERTS -> ActionableAlertNotifier.settingsIntent(this)
            }
            startActivity(settingsIntent)
            Toast.makeText(
                this,
                "Allow PaisaLens notifications in Android Settings, then return to finish enabling them.",
                Toast.LENGTH_LONG,
            ).show()
        } else {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /** Enables only the feature that initiated the permission request. */
    private fun completePendingNotificationPermission(): Boolean {
        val purpose = pendingNotificationPurpose ?: return false
        val allowed = when (purpose) {
            NotificationPermissionPurpose.DIGEST -> hasNotificationPermission
            NotificationPermissionPurpose.ACTIONABLE_ALERTS -> hasActionableAlertPermission
        }
        if (!allowed) return false
        when (purpose) {
            NotificationPermissionPurpose.DIGEST -> viewModel.setNotificationDigestEnabled(true)
            NotificationPermissionPurpose.ACTIONABLE_ALERTS -> viewModel.setActionableAlertsEnabled(true)
        }
        pendingNotificationPurpose = null
        return true
    }

    @Suppress("DEPRECATION")
    private fun authenticate(purpose: AuthenticationPurpose) {
        if (authPromptVisible) return
        val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (!keyguard.isDeviceSecure) {
            Toast.makeText(this, "Set a screen lock in Android Settings first", Toast.LENGTH_LONG).show()
            return
        }
        authenticationPurpose = purpose
        authPromptVisible = true
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    authPromptVisible = false
                    if (authenticationPurpose == AuthenticationPurpose.ENABLE_LOCK) viewModel.setAppLock(true)
                    isUnlocked = true
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    authPromptVisible = false
                    if (errorCode != BiometricPrompt.ERROR_USER_CANCELED && errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                        Toast.makeText(this@MainActivity, errString, Toast.LENGTH_SHORT).show()
                    }
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(if (purpose == AuthenticationPurpose.ENABLE_LOCK) "Enable PaisaLens app lock" else "Unlock PaisaLens")
            .setSubtitle("Use your fingerprint, face, PIN, pattern, or password")
            .setDeviceCredentialAllowed(true)
            .build()
        prompt.authenticate(info)
    }

    private fun changeAppLock(enabled: Boolean) {
        if (enabled) authenticate(AuthenticationPurpose.ENABLE_LOCK) else viewModel.setAppLock(false)
    }

    private fun captureReceipt() {
        runCatching {
            val receiptDirectory = File(cacheDir, "receipts").apply { mkdirs() }
            val file = File.createTempFile("receipt-", ".jpg", receiptDirectory)
            FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        }.onSuccess { uri ->
            pendingReceiptUri = uri
            receiptCameraLauncher.launch(uri)
        }.onFailure {
            Toast.makeText(this, "Could not open the camera", Toast.LENGTH_SHORT).show()
        }
    }

    private fun composeBalanceSms(account: AccountProfile) {
        if (!hasSmsPermission) {
            Toast.makeText(this, "Enable SMS access so PaisaLens can read the bank reply", Toast.LENGTH_LONG).show()
            smsPermissionLauncher.launch(
                arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS),
            )
            return
        }
        val command = BankSmsSupport.commandFor(account)
        if (command == null) {
            Toast.makeText(
                this,
                "No verified SMS enquiry command is available for ${account.name}",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        val primaryIntent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${command.destination}")).apply {
            putExtra("sms_body", command.message)
            putExtra(Intent.EXTRA_TEXT, command.message)
        }
        val fallbackIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("sms:${command.destination}?body=${Uri.encode(command.message)}"),
        ).apply {
            putExtra("sms_body", command.message)
            putExtra(Intent.EXTRA_TEXT, command.message)
        }
        val opened = openSmsComposer(primaryIntent) || openSmsComposer(fallbackIntent)
        if (!opened) {
            Toast.makeText(this, "No SMS app is available", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(
            this,
            "Review and send the ${command.bankName} enquiry. SMS charges may apply.",
            Toast.LENGTH_LONG,
        ).show()
    }

    private fun openSmsComposer(intent: Intent): Boolean = try {
        // Starting an implicit intent is allowed even when Android package visibility hides it
        // from resolveActivity(), so launch directly and handle the genuinely missing-app case.
        startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    private fun refreshAvailableUpiApps() {
        availableUpiApps = SUPPORTED_UPI_APPS.filter { app ->
            packageManager.getLaunchIntentForPackage(app.packageName) != null
        }
    }

    private fun launchUpiApp(app: UpiAppChoice): Boolean {
        if (SUPPORTED_UPI_APPS.none { it.packageName == app.packageName }) return false
        val launchIntent = packageManager.getLaunchIntentForPackage(app.packageName) ?: return false
        return try {
            startActivity(launchIntent)
            Toast.makeText(
                this,
                "Check the balance in ${app.displayName}, then return to PaisaLens",
                Toast.LENGTH_LONG,
            ).show()
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    private enum class AuthenticationPurpose {
        UNLOCK,
        ENABLE_LOCK,
    }

    private enum class NotificationPermissionPurpose {
        DIGEST,
        ACTIONABLE_ALERTS,
    }

    private companion object {
        const val STATE_PENDING_NOTIFICATION_PURPOSE = "pending_notification_purpose"

        val SUPPORTED_UPI_APPS = listOf(
            UpiAppChoice("in.org.npci.upiapp", "BHIM"),
            UpiAppChoice("com.google.android.apps.nbu.paisa.user", "Google Pay"),
            UpiAppChoice("com.phonepe.app", "PhonePe"),
            UpiAppChoice("net.one97.paytm", "Paytm"),
            UpiAppChoice("com.dreamplug.androidapp", "CRED"),
            UpiAppChoice("in.amazon.mShop.android.shopping", "Amazon Pay"),
            UpiAppChoice("com.whatsapp", "WhatsApp"),
        )
    }
}
