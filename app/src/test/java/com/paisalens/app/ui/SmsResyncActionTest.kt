package com.paisalens.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SmsResyncActionTest {
    @Test
    fun startsScanWhenPermissionIsAvailable() {
        assertEquals(
            SmsResyncAction.START_SCAN,
            smsResyncAction(hasSmsPermission = true, isScanning = false),
        )
    }

    @Test
    fun requestsPermissionWhenSmsAccessIsMissing() {
        assertEquals(
            SmsResyncAction.REQUEST_PERMISSION,
            smsResyncAction(hasSmsPermission = false, isScanning = false),
        )
    }

    @Test
    fun ignoresRepeatedPullWhileScanIsRunning() {
        assertEquals(
            SmsResyncAction.IGNORE_WHILE_SCANNING,
            smsResyncAction(hasSmsPermission = true, isScanning = true),
        )
    }
}
