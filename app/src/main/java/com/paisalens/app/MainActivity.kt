package com.paisalens.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.paisalens.app.ui.PaisaLensApp
import com.paisalens.app.ui.PaisaLensViewModel
import java.time.LocalDate

class MainActivity : ComponentActivity() {
    private val viewModel: PaisaLensViewModel by viewModels {
        PaisaLensViewModel.Factory(application as PaisaLensApplication)
    }

    private var hasSmsPermission by mutableStateOf(false)

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        updatePermissionState()

        setContent {
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
            )
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionState()
    }

    private fun updatePermissionState() {
        hasSmsPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.READ_SMS,
        ) == PackageManager.PERMISSION_GRANTED
    }
}
