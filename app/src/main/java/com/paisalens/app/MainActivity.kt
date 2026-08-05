package com.paisalens.app

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.paisalens.app.ui.PaisaLensApp
import com.paisalens.app.ui.PaisaLensViewModel
import com.paisalens.app.ui.AppLockScreen
import java.time.LocalDate

class MainActivity : FragmentActivity() {
    private val viewModel: PaisaLensViewModel by viewModels {
        PaisaLensViewModel.Factory(application as PaisaLensApplication)
    }

    private var hasSmsPermission by mutableStateOf(false)
    private var pendingBackupPassphrase: CharArray? = null
    private var pendingRestorePassphrase: CharArray? = null
    private var pendingStatementAccountId: Long? = null
    private var isUnlocked by mutableStateOf(true)
    private var authPromptVisible = false
    private var authenticationPurpose = AuthenticationPurpose.UNLOCK

    private val smsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        hasSmsPermission = grants[Manifest.permission.READ_SMS] == true
        if (hasSmsPermission) viewModel.scanSms(this)
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

    private val statementImportLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val accountId = pendingStatementAccountId
        pendingStatementAccountId = null
        uri?.let { viewModel.previewStatement(contentResolver, it, accountId) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        updatePermissionState()
        isUnlocked = !(application as PaisaLensApplication).preferences.appLockEnabled

        setContent {
            if (isUnlocked) {
                PaisaLensApp(
                    viewModel = viewModel,
                    hasSmsPermission = hasSmsPermission,
                    onRequestSmsPermission = {
                        smsPermissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.READ_SMS,
                                Manifest.permission.RECEIVE_SMS,
                            ),
                        )
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
                    onAppLockChange = ::changeAppLock,
                )
            } else {
                AppLockScreen(onUnlock = { authenticate(AuthenticationPurpose.UNLOCK) })
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionState()
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

    private enum class AuthenticationPurpose {
        UNLOCK,
        ENABLE_LOCK,
    }
}
