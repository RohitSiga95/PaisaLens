package com.paisalens.app.ui.privacy

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size

/**
 * Process-local privacy signal used by the shared money formatter. Reading [active] from a
 * composition participates in snapshot observation, so a one-tap toggle refreshes every existing
 * formatted amount without threading another Boolean through every dashboard and sheet.
 */
object PrivacyModeRuntime {
    var active: Boolean by mutableStateOf(false)
        private set
    private var initialized = false

    fun initialize(defaultActive: Boolean) {
        if (!initialized) {
            active = defaultActive
            initialized = true
        }
    }

    fun update(active: Boolean) {
        this.active = active
        initialized = true
    }
}

fun maskMoneyText(formattedAmount: String, privacyActive: Boolean): String =
    if (privacyActive) MASKED_MONEY_TEXT else formattedAmount

/** Adds screenshot and Recents-preview protection only while privacy mode owns the flag. */
@Composable
fun SecurePrivacyWindowEffect(
    active: Boolean,
    protectScreenCapture: Boolean = true,
) {
    val window = LocalView.current.context.findActivity()?.window
    val enabled = active && protectScreenCapture
    DisposableEffect(window, enabled) {
        val alreadySecure = window?.attributes?.flags
            ?.and(WindowManager.LayoutParams.FLAG_SECURE) != 0
        if (enabled && !alreadySecure) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        onDispose {
            if (enabled && !alreadySecure) {
                window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }
}

/** Accessible 48dp action suitable for a Home or top-app-bar amount-visibility control. */
@Composable
fun PrivacyModeToggleButton(
    active: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onToggle,
        modifier = modifier
            .size(48.dp)
            .semantics {
                role = Role.Switch
                stateDescription = if (active) "Amounts hidden" else "Amounts visible"
            },
    ) {
        Icon(
            imageVector = if (active) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
            contentDescription = if (active) "Show amounts for this session" else "Hide amounts for this session",
        )
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

const val MASKED_MONEY_TEXT = "••••"
