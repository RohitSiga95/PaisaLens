package com.paisalens.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.paisalens.app.data.model.AccountProfile
import com.paisalens.app.ui.components.formatMoney
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * A launch target supplied by the Android host after it has resolved an installed UPI app.
 * PaisaLens only opens the app; no balance or authentication data is returned through this model.
 */
data class UpiAppChoice(
    val packageName: String,
    val displayName: String,
)

/**
 * A balance the user explicitly typed after checking it in a UPI app.
 * [recordedAt] is the save time, not a timestamp reported by the UPI app.
 */
internal data class UserEnteredBalanceRecord(
    val accountId: Long,
    val balanceMinor: Long,
    val recordedAt: Long,
    val sourceLabel: String,
)

internal data class UserBalanceValidation(
    val balanceMinor: Long? = null,
    val errorMessage: String? = null,
) {
    val isValid: Boolean
        get() = balanceMinor != null && errorMessage == null
}

private enum class UpiBalanceStep {
    CHOOSE_APP,
    RECORD_BALANCE,
}

/**
 * Privacy-safe UPI balance hand-off.
 *
 * The selected UPI app is launched by [onLaunchUpiApp]. On return, PaisaLens asks the user to
 * type the balance they saw. This component never requests a UPI PIN, reads another app's UI, or
 * claims that the typed value was verified by the bank.
 *
 * [onLaunchUpiApp] should return false when Android cannot open the selected package. The caller
 * should persist [UserEnteredBalanceRecord] as a manual/user-entered balance history point. Keep
 * [isSaving] true until that asynchronous write finishes; the sheet then blocks duplicate saves,
 * navigation, dismissal, and swipe-to-dismiss. Surface a failed write through [errorMessage].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UpiBalanceCheckSheet(
    account: AccountProfile,
    availableUpiApps: List<UpiAppChoice>,
    amountInput: String,
    onAmountInputChange: (String) -> Unit,
    onLaunchUpiApp: (UpiAppChoice) -> Boolean,
    onSaveBalance: (UserEnteredBalanceRecord) -> Unit,
    onDismiss: () -> Unit,
    isSaving: Boolean = false,
    errorMessage: String? = null,
    initialSelectedPackageName: String? = null,
    clockMillis: () -> Long = System::currentTimeMillis,
) {
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { !isSaving },
    )
    var stepName by rememberSaveable(account.id) {
        mutableStateOf(
            if (initialSelectedPackageName == null) {
                UpiBalanceStep.CHOOSE_APP.name
            } else {
                UpiBalanceStep.RECORD_BALANCE.name
            },
        )
    }
    var selectedPackage by rememberSaveable(account.id) { mutableStateOf(initialSelectedPackageName) }
    var submitted by rememberSaveable(account.id) { mutableStateOf(false) }
    var launchError by rememberSaveable(account.id) { mutableStateOf<String?>(null) }
    val step = runCatching { UpiBalanceStep.valueOf(stepName) }.getOrDefault(UpiBalanceStep.CHOOSE_APP)
    val selectedApp = availableUpiApps.firstOrNull { it.packageName == selectedPackage }

    ModalBottomSheet(
        onDismissRequest = { if (!isSaving) onDismiss() },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.94f)
                .navigationBarsPadding(),
        ) {
            UpiBalanceHeader(
                title = if (step == UpiBalanceStep.CHOOSE_APP) "Check balance via UPI" else "Record checked balance",
                subtitle = account.name + (account.accountHint?.let { " · •••• $it" } ?: ""),
                canGoBack = step == UpiBalanceStep.RECORD_BALANCE,
                enabled = !isSaving,
                onBack = {
                    stepName = UpiBalanceStep.CHOOSE_APP.name
                    launchError = null
                },
                onDismiss = onDismiss,
            )

            when (step) {
                UpiBalanceStep.CHOOSE_APP -> UpiAppChooserContent(
                    availableUpiApps = availableUpiApps,
                    launchError = launchError,
                    onOpenApp = { app ->
                        launchError = null
                        if (onLaunchUpiApp(app)) {
                            selectedPackage = app.packageName
                            stepName = UpiBalanceStep.RECORD_BALANCE.name
                        } else {
                            launchError = "Could not open ${app.displayName}. Check that it is installed and try again."
                        }
                    },
                    onAlreadyChecked = {
                        selectedPackage = null
                        launchError = null
                        stepName = UpiBalanceStep.RECORD_BALANCE.name
                    },
                )

                UpiBalanceStep.RECORD_BALANCE -> {
                    val validation = validateUserEnteredBalance(amountInput)
                    UpiBalanceEntryContent(
                        account = account,
                        amountInput = amountInput,
                        selectedApp = selectedApp,
                        validation = validation,
                        submitted = submitted,
                        launchError = launchError,
                        saveError = errorMessage,
                        isSaving = isSaving,
                        onAmountChange = {
                            onAmountInputChange(it.userEnteredBalanceInput())
                            submitted = false
                        },
                        onOpenAgain = selectedApp?.let { app ->
                            {
                                launchError = if (onLaunchUpiApp(app)) {
                                    null
                                } else {
                                    "Could not reopen ${app.displayName}. You can still enter a balance you checked yourself."
                                }
                            }
                        },
                        onSave = {
                            submitted = true
                            validation.balanceMinor?.takeIf { validation.errorMessage == null }?.let { balanceMinor ->
                                val recordedAt = clockMillis()
                                onSaveBalance(
                                    UserEnteredBalanceRecord(
                                        accountId = account.id,
                                        balanceMinor = balanceMinor,
                                        recordedAt = recordedAt,
                                        sourceLabel = upiUserEnteredSourceLabel(selectedApp?.displayName),
                                    ),
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun UpiBalanceHeader(
    title: String,
    subtitle: String,
    canGoBack: Boolean,
    enabled: Boolean,
    onBack: () -> Unit,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = if (canGoBack) 8.dp else 20.dp, end = 8.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (canGoBack) {
            IconButton(onClick = onBack, enabled = enabled, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back to UPI app selection")
            }
            Spacer(Modifier.width(4.dp))
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = title,
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onDismiss, enabled = enabled, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Rounded.Close, contentDescription = "Close UPI balance check")
        }
    }
}

@Composable
private fun UpiAppChooserContent(
    availableUpiApps: List<UpiAppChoice>,
    launchError: String?,
    onOpenApp: (UpiAppChoice) -> Unit,
    onAlreadyChecked: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            UpiPrivacyNotice(
                "No balance is returned automatically or read from the UPI app. PaisaLens stores only the amount you explicitly type and save afterward.",
            )
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("Choose a UPI app", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "Open the app, use its Check bank balance feature, then return to PaisaLens.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (availableUpiApps.isEmpty()) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("No supported UPI app found", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "You can still record a balance that you checked yourself. PaisaLens will label it as user entered.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        } else {
            items(availableUpiApps, key = UpiAppChoice::packageName) { app ->
                UpiAppRow(app = app, onClick = { onOpenApp(app) })
            }
        }
        launchError?.let { message ->
            item { InlineUpiError(message) }
        }
        item {
            TextButton(
                onClick = onAlreadyChecked,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text("I already checked my balance")
            }
        }
        item {
            Text(
                "UPI apps do not send the displayed balance back to PaisaLens. This flow never reads another app's screen or notifications.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun UpiAppRow(
    app: UpiAppChoice,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .semantics {
                role = Role.Button
                contentDescription = "Open ${app.displayName} to check bank balance"
            },
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Icon(
                    Icons.Rounded.AccountBalanceWallet,
                    contentDescription = null,
                    modifier = Modifier.padding(10.dp).size(22.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(app.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "Check balance securely in this app",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null)
        }
    }
}

@Composable
private fun UpiBalanceEntryContent(
    account: AccountProfile,
    amountInput: String,
    selectedApp: UpiAppChoice?,
    validation: UserBalanceValidation,
    submitted: Boolean,
    launchError: String?,
    saveError: String?,
    isSaving: Boolean,
    onAmountChange: (String) -> Unit,
    onOpenAgain: (() -> Unit)?,
    onSave: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            UpiPrivacyNotice(
                "PaisaLens did not read or verify the UPI app. Enter only the balance you personally saw there.",
            )
        }
        account.balanceMinor?.let { currentBalance ->
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Currently saved", style = MaterialTheme.typography.labelLarge)
                            Text(
                                account.availabilityFetchedAt?.let { "Recorded ${formatUserBalanceTime(it)}" }
                                    ?: "Previous balance",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(formatMoney(currentBalance), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        item {
            OutlinedTextField(
                value = amountInput,
                onValueChange = onAmountChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Balance you saw") },
                prefix = { Text("₹") },
                placeholder = { Text("0.00") },
                singleLine = true,
                enabled = !isSaving,
                isError = submitted && !validation.isValid,
                supportingText = {
                    Text(
                        if (submitted && validation.errorMessage != null) {
                            validation.errorMessage
                        } else {
                            "Negative balances and overdrafts are allowed · Up to 13 digits and 2 decimals"
                        },
                    )
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal,
                    imeAction = ImeAction.Done,
                ),
            )
        }
        item {
            val isNegative = amountInput.trimStart().startsWith('-')
            OutlinedButton(
                onClick = {
                    onAmountChange(
                        if (isNegative) amountInput.trimStart().removePrefix("-") else "-$amountInput",
                    )
                },
                enabled = !isSaving,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(if (isNegative) "Use positive balance" else "Mark as overdraft / negative")
            }
        }
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.64f),
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(Icons.Rounded.CheckCircle, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("How this update is saved", style = MaterialTheme.typography.labelLarge)
                        Text(
                            "Source: ${upiUserEnteredSourceLabel(selectedApp?.displayName)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "Time: the moment you tap Save balance",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        launchError?.let { message ->
            item { InlineUpiError(message) }
        }
        saveError?.let { message ->
            item { InlineUpiError(message) }
        }
        onOpenAgain?.let { openAgain ->
            item {
                OutlinedButton(
                    onClick = openAgain,
                    enabled = !isSaving,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Open ${selectedApp?.displayName ?: "UPI app"} again")
                }
            }
        }
        item {
            Button(
                onClick = onSave,
                enabled = !isSaving,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = MaterialTheme.shapes.medium,
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Saving balance…")
                } else {
                    Text("Save balance")
                }
            }
        }
        item {
            Text(
                "This is a user-entered balance. It may differ from your bank's live balance if transactions are still pending.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun UpiPrivacyNotice(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.64f),
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(Icons.Rounded.Lock, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun InlineUpiError(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Text(message, modifier = Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

internal fun validateUserEnteredBalance(input: String): UserBalanceValidation {
    val clean = input.trim()
    if (clean.isBlank()) {
        return UserBalanceValidation(errorMessage = "Enter the balance shown in your UPI app.")
    }
    if (!USER_BALANCE_PATTERN.matches(clean)) {
        return UserBalanceValidation(errorMessage = "Enter a valid amount with no more than 2 decimal places.")
    }
    val balanceMinor = runCatching {
        BigDecimal(clean).movePointRight(2).longValueExact()
    }.getOrNull() ?: return UserBalanceValidation(errorMessage = "Enter a valid balance.")
    if (balanceMinor !in -MAX_USER_ENTERED_BALANCE_MINOR..MAX_USER_ENTERED_BALANCE_MINOR) {
        return UserBalanceValidation(errorMessage = "Enter a balance between -₹1 trillion and ₹1 trillion.")
    }
    return UserBalanceValidation(balanceMinor = balanceMinor)
}

internal fun String.userEnteredBalanceInput(): String {
    val clean = trimStart()
    val sign = if (clean.startsWith('-')) "-" else ""
    val unsigned = clean.filter { it.isDigit() || it == '.' }
    val firstDot = unsigned.indexOf('.')
    if (firstDot < 0) return sign + unsigned.take(13)

    val integerPart = unsigned.substring(0, firstDot).take(13).ifBlank { "0" }
    val decimalPart = unsigned
        .substring(firstDot + 1)
        .filter(Char::isDigit)
        .take(2)
    return sign + integerPart + "." + decimalPart
}

internal fun upiUserEnteredSourceLabel(appName: String?): String {
    val safeAppName = appName
        ?.trim()
        ?.replace(Regex("\\s+"), " ")
        ?.take(30)
        ?.takeIf(String::isNotBlank)
    return safeAppName?.let { "User entered after $it check" }
        ?: "User entered after UPI check"
}

private fun formatUserBalanceTime(timestamp: Long): String = Instant.ofEpochMilli(timestamp)
    .atZone(ZoneId.systemDefault())
    .format(DateTimeFormatter.ofPattern("d MMM, h:mm a", Locale.forLanguageTag("en-IN")))

private val USER_BALANCE_PATTERN = Regex("^-?\\d{1,13}(?:\\.\\d{1,2})?$")
private const val MAX_USER_ENTERED_BALANCE_MINOR = 100_000_000_000_000L
