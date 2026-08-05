package com.paisalens.app.ui

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.paisalens.app.PaisaLensApplication
import com.paisalens.app.data.model.ExpenseCategory
import com.paisalens.app.data.model.TransactionRecord
import com.paisalens.app.data.model.TransactionType
import com.paisalens.app.data.export.PaisaLensWorkbookExporter
import com.paisalens.app.sms.SmsInboxScanner
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PaisaLensViewModel(
    private val app: PaisaLensApplication,
) : ViewModel() {
    val transactions = app.repository.transactions
    val budgets = app.repository.budgets
    val categorizedMerchantKeys = app.repository.categorizedMerchantKeys

    private val _isScanning = MutableStateFlow(false)
    val isScanning = _isScanning.asStateFlow()

    private val _onboardingComplete = MutableStateFlow(app.preferences.onboardingComplete)
    val onboardingComplete = _onboardingComplete.asStateFlow()

    private val _darkMode = MutableStateFlow(app.preferences.darkMode)
    val darkMode = _darkMode.asStateFlow()

    private val _lastScanAt = MutableStateFlow(app.preferences.lastScanAt)
    val lastScanAt = _lastScanAt.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val events = _events.asSharedFlow()

    init {
        viewModelScope.launch { app.repository.load() }
    }

    fun completeOnboarding() {
        app.preferences.onboardingComplete = true
        _onboardingComplete.value = true
    }

    fun setDarkMode(enabled: Boolean) {
        app.preferences.darkMode = enabled
        _darkMode.value = enabled
    }

    fun scanSms(context: Context) {
        if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            _events.tryEmit("Enable SMS access to scan transaction alerts")
            return
        }
        if (_isScanning.value) return

        viewModelScope.launch {
            _isScanning.value = true
            runCatching {
                val parsed = SmsInboxScanner(context, app.parser).scan()
                app.repository.insertParsed(parsed)
            }.onSuccess { inserted ->
                val now = System.currentTimeMillis()
                app.preferences.lastScanAt = now
                _lastScanAt.value = now
                _events.emit(
                    if (inserted == 0) {
                        "You're up to date"
                    } else {
                        "$inserted new transaction" + (if (inserted == 1) "" else "s") + " added"
                    },
                )
            }.onFailure {
                _events.emit("Could not scan SMS. Check permission and try again")
            }
            _isScanning.value = false
        }
    }

    fun addManual(
        amountMinor: Long,
        merchant: String,
        category: ExpenseCategory,
        type: TransactionType,
        note: String?,
    ) {
        viewModelScope.launch {
            app.repository.addManual(amountMinor, merchant, category, type, note)
            _events.emit("Transaction added")
        }
    }

    fun updateCategory(
        transaction: TransactionRecord,
        category: ExpenseCategory,
    ) {
        viewModelScope.launch {
            val updated = app.repository.updateCategory(transaction, category)
            _events.emit(
                if (transaction.type == TransactionType.EXPENSE && updated > 1) {
                    "$updated ${transaction.merchant} expenses updated"
                } else {
                    "Category updated"
                },
            )
        }
    }

    fun updateMerchantCategory(merchant: String, category: ExpenseCategory) {
        viewModelScope.launch {
            val updated = app.repository.updateMerchantCategory(merchant, category)
            _events.emit(
                "$updated matching expense" + (if (updated == 1) "" else "s") + " updated",
            )
        }
    }

    fun updateNote(id: Long, note: String) {
        viewModelScope.launch {
            app.repository.updateNote(id, note)
            _events.emit(if (note.isBlank()) "Note removed" else "Note saved")
        }
    }

    fun deleteTransaction(id: Long) {
        viewModelScope.launch {
            app.repository.deleteTransaction(id)
            _events.emit("Transaction removed")
        }
    }

    fun setBudget(category: ExpenseCategory, limitMinor: Long) {
        viewModelScope.launch {
            app.repository.setBudget(category, limitMinor)
            _events.emit(if (limitMinor > 0) "Budget saved" else "Budget removed")
        }
    }

    fun exportWorkbook(contentResolver: ContentResolver, uri: Uri) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val output = contentResolver.openOutputStream(uri, "w")
                        ?: error("Could not open the selected file")
                    output.use {
                        PaisaLensWorkbookExporter.write(
                            transactions = transactions.value,
                            budgets = budgets.value,
                            outputStream = it,
                        )
                    }
                }
            }
            _events.emit(
                if (result.isSuccess) {
                    "Excel report exported"
                } else {
                    "Could not export Excel report"
                },
            )
        }
    }

    fun clearAll() {
        viewModelScope.launch {
            app.repository.clearAll()
            app.preferences.lastScanAt = 0L
            _lastScanAt.value = 0L
            _events.emit("All local financial data erased")
        }
    }

    class Factory(
        private val app: PaisaLensApplication,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return PaisaLensViewModel(app) as T
        }
    }
}
