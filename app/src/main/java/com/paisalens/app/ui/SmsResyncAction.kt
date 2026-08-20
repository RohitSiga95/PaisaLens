package com.paisalens.app.ui

internal enum class SmsResyncAction {
    START_SCAN,
    REQUEST_PERMISSION,
    IGNORE_WHILE_SCANNING,
}

internal fun smsResyncAction(
    hasSmsPermission: Boolean,
    isScanning: Boolean,
): SmsResyncAction = when {
    isScanning -> SmsResyncAction.IGNORE_WHILE_SCANNING
    hasSmsPermission -> SmsResyncAction.START_SCAN
    else -> SmsResyncAction.REQUEST_PERMISSION
}
